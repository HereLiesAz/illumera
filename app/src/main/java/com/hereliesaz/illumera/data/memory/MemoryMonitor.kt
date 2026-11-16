package com.hereliesaz.illumera.data.memory

import android.content.ComponentCallbacks2
import android.os.SystemClock
import coil.ImageLoader
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How short of memory the app is. */
enum class Pressure { Normal, High, Critical }

/** Who gives memory back first: the lower the rank, the less it is missed. */
object MemoryRank {
    /** A trailer buffered for a title nobody has reached yet. */
    const val SPECULATIVE_PREVIEW = 5
    /** Decoded images; they come back from the disk cache. */
    const val IMAGES = 10
    /** Home's fetched-ahead titles, lookups and rows far from the viewer. */
    const val HOME_DATA = 20
}

/** The share of the Java heap in use at which memory counts as [Pressure.High] / [Pressure.Critical]. */
internal const val HIGH_FRACTION = 0.70
internal const val CRITICAL_FRACTION = 0.85

internal fun pressureOf(usedBytes: Long, maxBytes: Long): Pressure {
    if (maxBytes <= 0L) return Pressure.Normal
    val used = usedBytes.toDouble() / maxBytes
    return when {
        used >= CRITICAL_FRACTION -> Pressure.Critical
        used >= HIGH_FRACTION -> Pressure.High
        else -> Pressure.Normal
    }
}

/** What Android's own low-memory signal means here, or null when it asks for nothing. */
internal fun pressureOfSystemTrim(level: Int): Pressure? = when {
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
        level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Pressure.Critical
    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> Pressure.Critical
    level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Pressure.High
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> Pressure.High
    else -> null
}

/**
 * Watches the app's memory and, when it runs short, makes room by dropping what is least useful
 * right now, least useful first.
 *
 * Whatever holds a cache registers a trimmer with a [MemoryRank]. When memory is [Pressure.High]
 * every trimmer is asked, lowest rank first, to drop what it can do without; at
 * [Pressure.Critical] to drop everything it can rebuild. A trimmer decides what is least useful
 * inside its own cache (the viewer's surroundings are what it keeps).
 *
 * The Java heap only shows the effect of a trim after the next collection, so the monitor does
 * not loop on a measurement: it acts, then waits out a cooldown before it acts again. High needs
 * two samples in a row (uncollected garbage can make one reading look worse than it is);
 * Critical acts at once.
 */
@Singleton
class MemoryMonitor @Inject constructor(imageLoader: ImageLoader) {
    private class Entry(val rank: Int, val trim: (Pressure) -> Unit)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var highSamples = 0
    private var lastActedAtMs = Long.MIN_VALUE / 2

    init {
        // Decoded images are the cheapest to give up: they reload from the disk cache. Half of
        // them go at High, all at Critical.
        register(MemoryRank.IMAGES) { pressure ->
            imageLoader.memoryCache?.trimMemory(
                if (pressure == Pressure.Critical) ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                else ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            )
        }
    }

    /** Adds a trimmer; close the result when its owner goes away. */
    fun register(rank: Int, trim: (Pressure) -> Unit): AutoCloseable {
        val entry = Entry(rank, trim)
        entries += entry
        return AutoCloseable { entries.remove(entry) }
    }

    /** Samples the heap every few seconds for as long as the app runs. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                val runtime = Runtime.getRuntime()
                val used = runtime.totalMemory() - runtime.freeMemory()
                val acted = onSample(used, runtime.maxMemory(), SystemClock.uptimeMillis())
                if (acted != Pressure.Normal) withContext(Dispatchers.Main) { trimAll(acted) }
            }
        }
    }

    /** Android is asking for memory back (see [pressureOfSystemTrim]). */
    suspend fun onSystemTrim(level: Int) {
        val pressure = pressureOfSystemTrim(level) ?: return
        withContext(Dispatchers.Main) { trimAll(pressure) }
        lastActedAtMs = SystemClock.uptimeMillis()
    }

    /**
     * One sample. Returns the pressure to act on now, or [Pressure.Normal] for none (memory is
     * fine, High has not lasted two samples, or a trim just ran).
     */
    internal fun onSample(usedBytes: Long, maxBytes: Long, nowMs: Long): Pressure {
        val pressure = pressureOf(usedBytes, maxBytes)
        if (pressure == Pressure.Normal) {
            highSamples = 0
            return Pressure.Normal
        }
        highSamples++
        val cooldown = if (pressure == Pressure.Critical) CRITICAL_COOLDOWN_MS else HIGH_COOLDOWN_MS
        if (nowMs - lastActedAtMs < cooldown) return Pressure.Normal
        if (pressure == Pressure.High && highSamples < 2) return Pressure.Normal
        lastActedAtMs = nowMs
        return pressure
    }

    /** Asks every trimmer to give memory back, least useful first. Main thread. */
    internal fun trimAll(pressure: Pressure) {
        entries.sortedBy { it.rank }.forEach { entry ->
            runCatching { entry.trim(pressure) }
                .onFailure { android.util.Log.w("MemoryMonitor", "A trimmer failed", it) }
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 3_000L
        const val HIGH_COOLDOWN_MS = 15_000L
        const val CRITICAL_COOLDOWN_MS = 5_000L
    }
}
