package com.hereliesaz.illumera.data.trakt

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watchlist changes made locally that Trakt has not confirmed yet.
 *
 * Watchlist sync treats "local but not on Trakt" as deleted on Trakt, and "on Trakt but
 * not local" as added there. A push that failed would be undone by that diff, so each
 * change is recorded here before it is pushed, cleared once Trakt accepts it, and
 * retried on the next sync. Kept on disk so a change survives the app being killed.
 */
@Singleton
class TraktWatchlistPendingStore @Inject constructor(
    @ApplicationContext context: Context
) {
    enum class Op { ADD, REMOVE }

    data class Pending(val id: String, val type: String, val op: Op)

    private val prefs = context.getSharedPreferences("trakt_watchlist_pending", Context.MODE_PRIVATE)

    fun mark(profileId: Int, id: String, type: String, op: Op) =
        prefs.edit { putString(key(profileId, id), "${op.name}|$type") }

    fun clear(profileId: Int, id: String) = prefs.edit { remove(key(profileId, id)) }

    fun pending(profileId: Int): List<Pending> {
        val prefix = "$profileId|"
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(prefix) || value !is String) return@mapNotNull null
            val op = runCatching { Op.valueOf(value.substringBefore('|')) }.getOrNull() ?: return@mapNotNull null
            Pending(key.removePrefix(prefix), value.substringAfter('|'), op)
        }
    }

    private fun key(profileId: Int, id: String) = "$profileId|$id"
}
