package com.hereliesaz.illumera.data.cache

import android.content.Context
import com.google.gson.Gson
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import java.io.File

private data class CachedRow(val configId: String = "", val items: List<MetaItem> = emptyList())
private data class CachedScreen(
    val savedAtMs: Long = 0L,
    val signature: String = "",
    val rows: List<CachedRow> = emptyList()
)

/**
 * The titles each main screen's rows last showed, kept in the app's cache directory so a screen
 * can open with them at once and then be brought up to date. Only catalog titles (no viewer data);
 * the system may clear it at any time, which just means a normal first load. Titles are only used
 * while the enabled addons are the ones they came from (the signature).
 */
class ScreenCacheStore(private val context: Context) {
    private val gson = Gson()

    private fun file(profileId: Int, screen: String) =
        File(File(context.cacheDir, "screens"), "${profileId}_$screen.json")

    /** configId -> titles, or null when nothing usable is saved. */
    fun load(profileId: Int, screen: String, maxAgeMs: Long, signature: String): Map<String, List<MetaItem>>? =
        try {
            val source = file(profileId, screen)
            if (!source.exists()) null else {
                val cached = gson.fromJson(source.readText(), CachedScreen::class.java)
                if (cached == null || cached.signature != signature ||
                    System.currentTimeMillis() - cached.savedAtMs > maxAgeMs
                ) null
                else cached.rows.filter { it.items.isNotEmpty() }.associate { it.configId to it.items }
                    .takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }

    fun save(profileId: Int, screen: String, signature: String, rows: Map<String, List<MetaItem>>) {
        try {
            val target = file(profileId, screen)
            target.parentFile?.mkdirs()
            val cached = CachedScreen(
                savedAtMs = System.currentTimeMillis(),
                signature = signature,
                rows = rows.filterValues { it.isNotEmpty() }.map { (configId, items) ->
                    // Episode lists are large and not needed to draw a row.
                    CachedRow(configId, items.take(MAX_ITEMS_PER_ROW).map { it.copy(videos = null) })
                }
            )
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(gson.toJson(cached))
            if (!temp.renameTo(target)) temp.delete()
        } catch (_: Exception) {
            // A cache that cannot be written is just a cache miss next time.
        }
    }

    private companion object {
        const val MAX_ITEMS_PER_ROW = 60
    }
}
