package com.hereliesaz.illumera.data.taste

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A viewer's verdict on a title. [weight] scales its pull on suggestions. */
enum class Taste(val weight: Int) { DISLIKE(-1), LIKE(1), LOVE(3) }

data class TasteEntry(
    val id: String,
    val type: String,
    val title: String,
    val taste: Taste,
    val genres: List<String> = emptyList(),
    val at: Long = System.currentTimeMillis()
)

/**
 * Per-profile Like / Love / Don't like verdicts set from the card long-press menu.
 *
 * Love and Like steer the personal rows (see SimilarTitlesEngine); Don't like hides the
 * title everywhere titles are suggested. Stored locally per profile; Love additionally
 * adds the title to the Trakt library (MediaActionsViewModel).
 */
@Singleton
class TasteStore @Inject constructor(
    @ApplicationContext context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager
) {
    private val prefs = context.getSharedPreferences("illumera_taste", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<TasteEntry>>() {}.type
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _entries = MutableStateFlow<Map<String, TasteEntry>>(emptyMap())
    /** Active profile's verdicts, keyed by title id. */
    val entries: StateFlow<Map<String, TasteEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            profileConfigurationManager.activeProfileId.collect { profileId ->
                _entries.value = profileId?.let(::read).orEmpty()
            }
        }
    }

    fun tasteOf(vararg ids: String?): Taste? =
        ids.firstNotNullOfOrNull { id -> id?.let { _entries.value[it]?.taste } }

    /** Records [taste] for every id the title is known by; null clears the verdict. */
    fun set(ids: Collection<String>, type: String, title: String, taste: Taste?, genres: List<String> = emptyList()) {
        val profileId = profileConfigurationManager.activeProfileId.value ?: return
        val updated = _entries.value.toMutableMap()
        ids.filter { it.isNotBlank() }.distinct().forEach { id ->
            if (taste == null) updated.remove(id)
            else updated[id] = TasteEntry(id, type, title, taste, genres)
        }
        _entries.value = updated
        prefs.edit().putString(key(profileId), gson.toJson(updated.values.toList())).apply()
    }

    private fun read(profileId: Int): Map<String, TasteEntry> = runCatching {
        val json = prefs.getString(key(profileId), null) ?: return emptyMap()
        gson.fromJson<List<TasteEntry>>(json, listType).orEmpty().associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun key(profileId: Int) = "p$profileId"
}

/** Whether any of [ids] carries a "Don't like" verdict in this snapshot. */
fun Map<String, TasteEntry>.dislikes(vararg ids: String?): Boolean =
    ids.any { id -> id != null && this[id]?.taste == Taste.DISLIKE }
