package com.hereliesaz.illumera.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.StremioApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stremio addon protocol `player` and `library` event resources: the addon receives the
 * viewer's activity (e.g. to sync a tracking service) and its response is ignored.
 *
 * Only addons whose manifest explicitly declares the resource receive events, filtered by
 * `types`/`idPrefixes` as for every other resource. An unreachable manifest means no events:
 * watch activity is never sent to an addon that has not asked for it.
 *
 * Each event is bound to the profile active when it happened: its addon snapshot is read
 * under that profile's runtime lock, and the event is dropped if the profile has changed.
 * Events reach each addon in the order they happened (one queue per addon); different
 * addons are served concurrently.
 *
 *   /player/{type}/{videoId}/action=start|pause|stop&currentTime={ms}&duration={ms}.json
 *   /library/{type}/{id}/action=libraryAdd|libraryRemove|watched|unwatched[&videoId=a,b].json
 */
@Singleton
class AddonEventDispatcher @Inject constructor(
    private val dao: AddonDao,
    private val api: StremioApiService,
    private val profiles: ProfileConfigurationManager
) {
    enum class PlayerAction(val wire: String) { START("start"), PAUSE("pause"), STOP("stop") }
    enum class LibraryAction(val wire: String) {
        ADD("libraryAdd"), REMOVE("libraryRemove"), WATCHED("watched"), UNWATCHED("unwatched")
    }

    private data class Rule(val types: Set<String>, val idPrefixes: Set<String>)

    private data class Event(
        val profileId: Int,
        val resource: String,
        val type: String,
        val id: String,
        val extras: List<String>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    // transportUrl -> resource name -> rules; an empty map means the manifest declares neither.
    private val rulesCache = ConcurrentHashMap<String, Map<String, List<Rule>>>()
    // Every event, in the order it happened; routed one at a time so order survives.
    private val events = Channel<Event>(Channel.UNLIMITED)
    // transportUrl -> that addon's own delivery queue, drained sequentially.
    private val addonQueues = ConcurrentHashMap<String, Channel<Pair<AddonEntity, Event>>>()

    init {
        scope.launch { for (event in events) route(event) }
    }

    fun player(type: String, videoId: String, action: PlayerAction, positionMs: Long, durationMs: Long) {
        val extra = "action=${action.wire}&currentTime=${positionMs.coerceAtLeast(0)}" +
            "&duration=${durationMs.coerceAtLeast(0)}"
        dispatch(RESOURCE_PLAYER, type, videoId, listOf(extra))
    }

    /** [videoIds] narrows watched/unwatched to specific videos, sent at most 100 per event. */
    fun library(type: String, id: String, action: LibraryAction, videoIds: List<String> = emptyList()) {
        val base = "action=${action.wire}"
        val extras = if (videoIds.isEmpty()) listOf(base) else videoIds.chunked(VIDEO_IDS_PER_EVENT).map { chunk ->
            "$base&videoId=${chunk.joinToString(",") { encode(it) }}"
        }
        dispatch(RESOURCE_LIBRARY, type, id, extras)
    }

    private fun dispatch(resource: String, type: String, id: String, extras: List<String>) {
        if (type.isBlank() || id.isBlank()) return
        val profileId = profiles.activeProfileId.value ?: return
        events.trySend(Event(profileId, resource, type, id, extras))
    }

    private suspend fun route(event: Event) {
        val addons = try {
            profiles.withActiveProfileRuntime(event.profileId) {
                dao.getAllAddons().firstOrNull()?.filter { it.isEnabled }.orEmpty()
            }
        } catch (_: CancellationException) {
            return // The originating profile is no longer active: its addons are gone.
        }
        addons.forEach { addon -> queueFor(addon.transportUrl).trySend(addon to event) }
    }

    private fun queueFor(transportUrl: String): Channel<Pair<AddonEntity, Event>> =
        addonQueues.computeIfAbsent(transportUrl) {
            Channel<Pair<AddonEntity, Event>>(Channel.UNLIMITED).also { queue ->
                scope.launch { for ((addon, event) in queue) deliver(addon, event) }
            }
        }

    private suspend fun deliver(addon: AddonEntity, event: Event) {
        if (!accepts(addon, event.resource, event.type, event.id)) return
        val base = addon.transportUrl.trimEnd('/')
        event.extras.forEach { extra ->
            // Fire and forget: the response carries nothing the app uses.
            withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                runCatching { api.getJson("$base/${event.resource}/${event.type}/${event.id}/$extra.json") }
            }
        }
    }

    private suspend fun accepts(addon: AddonEntity, resource: String, type: String, id: String): Boolean {
        val rules = rulesFor(addon)[resource] ?: return false
        val lowerType = type.lowercase(Locale.ROOT)
        val lowerId = id.lowercase(Locale.ROOT)
        return rules.any { rule ->
            (rule.types.isEmpty() || lowerType in rule.types) &&
                (rule.idPrefixes.isEmpty() || rule.idPrefixes.any { lowerId.startsWith(it) })
        }
    }

    private suspend fun rulesFor(addon: AddonEntity): Map<String, List<Rule>> {
        rulesCache[addon.transportUrl]?.let { return it }
        val manifest = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
            runCatching { api.getManifest("${addon.transportUrl.trimEnd('/')}/manifest.json") }.getOrNull()
        } ?: return emptyMap() // Not cached: a later event retries the manifest.
        // A resource given as a bare string inherits the manifest-level filters.
        val manifestRule = Rule(
            types = lowered(manifest.types ?: parseList(addon.typesJson)),
            idPrefixes = lowered(manifest.idPrefixes ?: parseList(addon.idPrefixesJson))
        )
        val rules = mutableMapOf<String, MutableList<Rule>>()
        manifest.resources.orEmpty().forEach { resource ->
            parseResource(resource, manifestRule)?.let { (name, rule) ->
                if (name == RESOURCE_PLAYER || name == RESOURCE_LIBRARY) rules.getOrPut(name) { mutableListOf() }.add(rule)
            }
        }
        return rules.also { rulesCache[addon.transportUrl] = it }
    }

    private fun parseResource(resource: JsonElement, manifestRule: Rule): Pair<String, Rule>? {
        if (resource.isJsonPrimitive && resource.asJsonPrimitive.isString) {
            return resource.asString.trim().lowercase(Locale.ROOT) to manifestRule
        }
        if (!resource.isJsonObject) return null
        val obj = resource.asJsonObject
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase(Locale.ROOT)
            ?: return null
        val types = obj.get("types")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() }
        val prefixes = obj.get("idPrefixes")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() }
        return name to Rule(
            types = types?.let(::lowered) ?: manifestRule.types,
            idPrefixes = prefixes?.let(::lowered) ?: manifestRule.idPrefixes
        )
    }

    private fun parseList(json: String): List<String> =
        runCatching { gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) }
            .getOrNull().orEmpty()

    private fun lowered(values: List<String>): Set<String> =
        values.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT) }.toSet()

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val RESOURCE_PLAYER = "player"
        const val RESOURCE_LIBRARY = "library"
        const val VIDEO_IDS_PER_EVENT = 100
        const val EVENT_TIMEOUT_MS = 10_000L
        const val MANIFEST_TIMEOUT_MS = 8_000L
    }
}
