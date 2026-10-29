// androidx.security-crypto (EncryptedSharedPreferences, MasterKey) is deprecated without a
// replacement; migrating would make stored credentials unreadable, so its use stays.
@file:Suppress("DEPRECATION")

package com.hereliesaz.illumera.data.debrid

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.data.debrid.providers.AllDebridService
import com.hereliesaz.illumera.data.debrid.providers.DebridLinkService
import com.hereliesaz.illumera.data.debrid.providers.EasyDebridService
import com.hereliesaz.illumera.data.debrid.providers.OffcloudService
import com.hereliesaz.illumera.data.debrid.providers.PremiumizeService
import com.hereliesaz.illumera.data.debrid.providers.RealDebridService
import com.hereliesaz.illumera.data.debrid.providers.TorBoxService
import com.hereliesaz.illumera.data.debrid.providers.TorrinService
import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridConnection
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.profile.ProfileMutationCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val profileMutationCoordinator: ProfileMutationCoordinator,
    private val realDebrid: RealDebridService,
    private val allDebrid: AllDebridService,
    private val premiumize: PremiumizeService,
    private val torBox: TorBoxService,
    private val debridLink: DebridLinkService,
    private val offcloud: OffcloudService,
    private val easyDebrid: EasyDebridService,
    private val torrin: TorrinService
) {
    companion object {
        private const val TAG = "DebridManager"
        private const val PREFS_NAME = "debrid_auth"
        /** Legacy single-service keys (one provider per profile); migrated on first read. */
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_USERNAME = "username"
        /** Comma-separated provider ids, primary first. */
        private const val KEY_PROVIDERS = "providers"
        private const val POLL_MS = 5_000L
        private const val STALL_BAIL_MS = 60_000L
    }

    private val prefs: SharedPreferences? by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // Corrupted file or key mismatch: drop only the debrid prefs file (never the
            // shared master key, which other integrations also use) and try again.
            com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Debrid secure prefs corrupted, resetting", e)
            clearCorruptedPrefs()
            try {
                createEncryptedPrefs()
            } catch (e2: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Debrid secure storage unavailable", e2)
                null
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearCorruptedPrefs() {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        File(prefsDir, "$PREFS_NAME.xml").delete()
        File(prefsDir, "$PREFS_NAME.xml.bak").delete()
    }

    private fun activeProfileId(): Int? = profileConfigurationManager.getLastActiveProfileId()
    private fun profileKey(key: String, profileId: Int) = "${key}_$profileId"
    private fun providerKey(key: String, provider: DebridProvider, profileId: Int) =
        "${key}_${provider.id}_$profileId"

    /** Every service connected to the active profile, primary first. */
    private val _connections = MutableStateFlow<List<DebridConnection>>(emptyList())
    val connections: StateFlow<List<DebridConnection>> = _connections

    /** The primary service: the one Torrentio and other single-key consumers use. */
    private val _connectedProvider = MutableStateFlow<DebridProvider?>(null)
    val connectedProvider: StateFlow<DebridProvider?> = _connectedProvider

    private val _connectedUsername = MutableStateFlow<String?>(null)
    val connectedUsername: StateFlow<String?> = _connectedUsername

    init { refreshConnectionState() }

    fun refreshConnectionState() {
        val profileId = activeProfileId()
        publish(if (profileId == null) emptyList() else readConnections(profileId))
    }

    private fun publish(list: List<DebridConnection>) {
        _connections.value = list
        _connectedProvider.value = list.firstOrNull()?.provider
        _connectedUsername.value = list.firstOrNull()?.username
    }

    private fun readProviders(profileId: Int): List<DebridProvider> {
        val p = prefs ?: return emptyList()
        migrateLegacy(p, profileId)
        return p.getString(profileKey(KEY_PROVIDERS, profileId), null).orEmpty()
            .split(',').mapNotNull { DebridProvider.fromId(it.trim()) }.distinct()
    }

    private fun readConnections(profileId: Int): List<DebridConnection> =
        readProviders(profileId).map { provider ->
            DebridConnection(provider, prefs?.getString(providerKey(KEY_USERNAME, provider, profileId), null))
        }

    /** Moves a pre-multi-service single connection into the per-provider layout. */
    private fun migrateLegacy(p: SharedPreferences, profileId: Int) {
        val legacy = DebridProvider.fromId(p.getString(profileKey(KEY_PROVIDER, profileId), null)) ?: return
        val editor = p.edit()
        if (!p.contains(profileKey(KEY_PROVIDERS, profileId))) {
            editor.putString(profileKey(KEY_PROVIDERS, profileId), legacy.id)
            p.getString(profileKey(KEY_API_KEY, profileId), null)
                ?.let { editor.putString(providerKey(KEY_API_KEY, legacy, profileId), it) }
            p.getString(profileKey(KEY_USERNAME, profileId), null)
                ?.let { editor.putString(providerKey(KEY_USERNAME, legacy, profileId), it) }
        }
        editor.remove(profileKey(KEY_PROVIDER, profileId))
            .remove(profileKey(KEY_API_KEY, profileId))
            .remove(profileKey(KEY_USERNAME, profileId))
            .apply()
    }

    private fun serviceFor(provider: DebridProvider): DebridService = when (provider) {
        DebridProvider.REAL_DEBRID -> realDebrid
        DebridProvider.ALL_DEBRID -> allDebrid
        DebridProvider.PREMIUMIZE -> premiumize
        DebridProvider.TORBOX -> torBox
        DebridProvider.DEBRID_LINK -> debridLink
        DebridProvider.OFFCLOUD -> offcloud
        DebridProvider.EASY_DEBRID -> easyDebrid
        DebridProvider.TORRIN -> torrin
    }

    /**
     * Runs a provider call, turning an unexpected parse/runtime exception into a Failure so
     * one malformed response cannot crash the caller. Cancellation is always rethrown.
     */
    private suspend fun <T> guarded(block: suspend () -> DebridResult<T>): DebridResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Debrid provider call failed", e)
            DebridResult.Failure(e.message ?: "Unexpected debrid response")
        }

    /** The key for [provider], or for the primary service when omitted. */
    fun getApiKey(provider: DebridProvider? = _connectedProvider.value): String? {
        val profileId = activeProfileId() ?: return null
        provider ?: return null
        return prefs?.getString(providerKey(KEY_API_KEY, provider, profileId), null)
    }

    /** Every connected service with its key, primary first. */
    fun connectedKeys(): List<Pair<DebridProvider, String>> =
        _connections.value.mapNotNull { c -> getApiKey(c.provider)?.takeIf { it.isNotBlank() }?.let { c.provider to it } }

    suspend fun connect(provider: DebridProvider, apiKey: String): DebridResult<DebridAccountInfo> {
        val profileId = activeProfileId()
            ?: return DebridResult.Failure("No active profile")
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) return DebridResult.Failure("API key cannot be empty")

        return when (val result = guarded { serviceFor(provider).validateApiKey(trimmedKey) }) {
            is DebridResult.Success -> {
                // Credential persistence and profile deletion share one process-wide
                // coordinator. If deletion won the race, do not recreate keys for an ID
                // that SQLite may later reuse for a different profile.
                val securePrefs = prefs
                    ?: return DebridResult.Failure("Secure storage unavailable")
                // Adds the service, or refreshes its key in place; the primary is kept.
                val saved = profileMutationCoordinator.withExistingProfile(profileId) {
                    val providers = (readProviders(profileId) + provider).distinct()
                    securePrefs.edit()
                        .putString(profileKey(KEY_PROVIDERS, profileId), providers.joinToString(",") { it.id })
                        .putString(providerKey(KEY_API_KEY, provider, profileId), trimmedKey)
                        .putString(providerKey(KEY_USERNAME, provider, profileId), result.value.username)
                        .apply()
                    true
                } == true
                if (!saved) return DebridResult.Failure("Profile no longer exists")
                if (activeProfileId() == profileId) publish(readConnections(profileId))
                result
            }
            is DebridResult.Failure -> {
                Log.w(TAG, "Failed to connect to ${provider.displayName}: ${result.message}")
                result
            }
        }
    }

    /** Disconnects [provider] from the active profile; the next service becomes primary. */
    fun disconnect(provider: DebridProvider) {
        val profileId = activeProfileId() ?: return
        val p = prefs ?: return
        val remaining = readProviders(profileId) - provider
        p.edit()
            .putString(profileKey(KEY_PROVIDERS, profileId), remaining.joinToString(",") { it.id })
            .remove(providerKey(KEY_API_KEY, provider, profileId))
            .remove(providerKey(KEY_USERNAME, provider, profileId))
            .apply()
        publish(readConnections(profileId))
    }

    /** Makes [provider] the primary service (first in order). */
    fun setPrimary(provider: DebridProvider) {
        val profileId = activeProfileId() ?: return
        val p = prefs ?: return
        val providers = readProviders(profileId)
        if (provider !in providers) return
        p.edit()
            .putString(profileKey(KEY_PROVIDERS, profileId), (listOf(provider) + (providers - provider)).joinToString(",") { it.id })
            .apply()
        publish(readConnections(profileId))
    }

    fun clearForProfile(profileId: Int) {
        val p = prefs ?: return
        val editor = p.edit()
        DebridProvider.entries.forEach {
            editor.remove(providerKey(KEY_API_KEY, it, profileId))
            editor.remove(providerKey(KEY_USERNAME, it, profileId))
        }
        editor.remove(profileKey(KEY_PROVIDERS, profileId))
            .remove(profileKey(KEY_PROVIDER, profileId))
            .remove(profileKey(KEY_API_KEY, profileId))
            .remove(profileKey(KEY_USERNAME, profileId))
            .apply()
    }

    /**
     * Every connected service's library, merged in connection order. One failing service
     * doesn't hide the others; only when all fail is the first failure returned.
     */
    suspend fun listLibrary(): DebridResult<List<DebridItem>> {
        val keys = connectedKeys()
        if (keys.isEmpty()) return DebridResult.Failure("No debrid service connected")
        val results = coroutineScope {
            keys.map { (provider, key) -> async { guarded { serviceFor(provider).listLibrary(key) } } }.awaitAll()
        }
        val items = results.filterIsInstance<DebridResult.Success<List<DebridItem>>>()
        return if (items.isEmpty()) results.first() else DebridResult.Success(items.flatMap { it.value })
    }

    suspend fun getStreamUrl(item: DebridItem): DebridResult<String> {
        val apiKey = getApiKey(item.provider)
            ?: return DebridResult.Failure("${item.provider.displayName} is not connected")
        return guarded { serviceFor(item.provider).getStreamUrl(apiKey, item) }
    }

    suspend fun deleteItem(item: DebridItem): DebridResult<Unit> {
        val apiKey = getApiKey(item.provider)
            ?: return DebridResult.Failure("${item.provider.displayName} is not connected")
        return guarded { serviceFor(item.provider).deleteItem(apiKey, item) }
    }

    /**
     * Polls the connected debrid service for the torrent/file represented by a placeholder
     * clip. We wait only while the observed progress rate says completion is plausible
     * inside [maxWaitSeconds]; otherwise the caller can immediately try the next source.
     */
    suspend fun awaitPlayableSource(
        infoHash: String?,
        fileName: String?,
        maxWaitSeconds: Int,
        onProgress: (percent: Int) -> Unit = {}
    ): String? {
        if (maxWaitSeconds <= 0) return null
        if (connectedKeys().isEmpty()) return null
        val wantedHash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (wantedHash == null && normalizeFileName(fileName) == null) return null

        val started = System.currentTimeMillis()
        val deadline = started + maxWaitSeconds * 1_000L
        var previousProgress: Int? = null
        var previousSampleAt = started
        var lastAdvanceAt = started

        while (System.currentTimeMillis() < deadline) {
            // Polls every connected service: the placeholder may live in any of them.
            val library = when (val result = listLibrary()) {
                is DebridResult.Success -> result.value
                is DebridResult.Failure -> return null
            }
            val item = library.firstOrNull { candidate ->
                val hashMatches = wantedHash != null && candidate.infoHash?.lowercase() == wantedHash
                hashMatches || fileNamesMatch(fileName, candidate.name)
            }

            if (item != null) {
                val progress = item.progress?.coerceIn(0, 100)
                val status = item.status?.lowercase().orEmpty()
                val ready = progress == 100 || status in setOf(
                    "downloaded", "ready", "finished", "completed", "cached", "seeding", "available"
                )
                if (ready) {
                    when (val stream = getStreamUrl(item)) {
                        is DebridResult.Success -> return stream.value
                        is DebridResult.Failure -> Unit
                    }
                }

                if (progress != null) {
                    onProgress(progress)
                    val now = System.currentTimeMillis()
                    val old = previousProgress
                    if (old != null) {
                        if (progress > old) lastAdvanceAt = now
                        val elapsedSampleSeconds = ((now - previousSampleAt).coerceAtLeast(1L)) / 1000.0
                        val rate = (progress - old).coerceAtLeast(0) / elapsedSampleSeconds
                        val secondsRemaining = ((deadline - now).coerceAtLeast(0L)) / 1000.0
                        if (rate > 0.0) {
                            val estimatedSeconds = (100 - progress) / rate
                            if (estimatedSeconds > secondsRemaining * 1.15) return null
                        } else if (progress in 1 until 90 && now - lastAdvanceAt >= STALL_BAIL_MS) {
                            // Only give up on a transfer that started and then stalled for a
                            // while; queued 0% transfers ride out the deadline instead.
                            return null
                        }
                    }
                    if (old == null) lastAdvanceAt = now
                    previousProgress = progress
                    previousSampleAt = now
                }
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            delay(minOf(POLL_MS, remaining))
        }
        return null
    }
}

/**
 * True when [wanted] and [candidate] name the same release: equal after normalization, or
 * one contains the other and both carry the same SxxEyy episode token. Containment alone
 * is too loose: a season pack or a neighbouring episode would otherwise match.
 */
internal fun fileNamesMatch(wanted: String?, candidate: String?): Boolean {
    val wantedName = normalizeFileName(wanted) ?: return false
    val candidateName = normalizeFileName(candidate) ?: return false
    if (wantedName == candidateName) return true
    if (!candidateName.contains(wantedName) && !wantedName.contains(candidateName)) return false
    val wantedEpisode = episodeToken(wanted) ?: return false
    return wantedEpisode == episodeToken(candidate)
}

private val EPISODE_TOKEN = Regex("""s(\d{1,2})[ ._-]?e(\d{1,3})""")

private fun episodeToken(name: String?): Pair<Int, Int>? {
    val match = EPISODE_TOKEN.find(name?.lowercase() ?: return null) ?: return null
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

internal fun normalizeFileName(name: String?): String? {
    val value = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return value.substringAfterLast('/').substringBeforeLast('.', value.substringAfterLast('/'))
        .replace(Regex("[^a-z0-9]+"), "")
        .takeIf { it.length >= 6 }
}
