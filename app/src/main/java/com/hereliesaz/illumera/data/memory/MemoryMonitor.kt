package com.hereliesaz.illumera.data.memory

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.SystemClock
import coil.ImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
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

/**
 * How short of memory the whole device is, which the Java heap cannot show: hardware bitmaps live
 * in graphics memory and video buffers in native memory, shared with every other process. From
 * `ActivityManager.getMemoryInfo`: [availBytes] is what the system has free, [thresholdBytes] is
 * where it starts killing background processes, [lowMemory] is that state itself. [cacheBytes] and
 * [cacheMaxBytes] are the image memory cache's fill: when it is holding most of its limit and the
 * device is getting close to the threshold, the images are the likeliest thing to give back.
 */
internal fun pressureOfDevice(
    availBytes: Long,
    thresholdBytes: Long,
    lowMemory: Boolean,
    cacheBytes: Long = 0L,
    cacheMaxBytes: Long = 0L
): Pressure {
    if (lowMemory) return Pressure.Critical
    if (thresholdBytes <= 0L) return Pressure.Normal
    return when {
        availBytes <= thresholdBytes -> Pressure.Critical
        availBytes <= thresholdBytes * DEVICE_HIGH_HEADROOM -> Pressure.High
        availBytes <= thresholdBytes * DEVICE_CACHE_HEADROOM &&
            cacheMaxBytes > 0L && cacheBytes >= cacheMaxBytes * CACHE_FULL_FRACTION -> Pressure.High
        else -> Pressure.Normal
    }
}

/** The worse of two pressures. */
internal fun worse(a: Pressure, b: Pressure): Pressure = if (a.ordinal >= b.ordinal) a else b

/** Available memory within this many times the system's threshold is [Pressure.High]. */
internal const val DEVICE_HIGH_HEADROOM = 1.5

/** ...and within this many, while the image cache is nearly full, too. */
internal const val DEVICE_CACHE_HEADROOM = 3.0

/** The image cache counts as nearly full from this share of its limit. */
internal const val CACHE_FULL_FRACTION = 0.8

/**
 * The image memory cache's limit: [defaultBytes] (Coil's 15% of the heap class) unless the device
 * is small, where it is held to [CACHE_SHARE_OF_RAM] of the device's RAM (never below
 * [CACHE_FLOOR_BYTES]), because hardware bitmaps are graphics memory shared with the whole box.
 * Never above [defaultBytes], so bigger devices keep what they had.
 */
internal fun imageCacheLimitBytes(defaultBytes: Long, totalRamBytes: Long): Long {
    if (totalRamBytes <= 0L) return defaultBytes
    val cap = maxOf(CACHE_FLOOR_BYTES, (totalRamBytes * CACHE_SHARE_OF_RAM).toLong())
    return minOf(defaultBytes, cap)
}

internal const val CACHE_SHARE_OF_RAM = 0.035
internal const val CACHE_FLOOR_BYTES = 24L * 1024 * 1024

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
 *
 * The heap is not the whole picture: hardware bitmaps are graphics memory and video buffers are
 * native memory, shared with the whole device. Each sample also asks `ActivityManager` how much
 * the system has free against its low-memory threshold ([pressureOfDevice]), and the worse of the
 * two readings decides. Android's own `onTrimMemory` / `onLowMemory` go through [onSystemTrim].
 */
@Singleton
class MemoryMonitor @Inject constructor(
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context
) {
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
                val acted = onSample(used, runtime.maxMemory(), SystemClock.uptimeMillis(), devicePressure())
                if (acted != Pressure.Normal) withContext(Dispatchers.Main) { trimAll(acted) }
            }
        }
    }

    /**
     * Device-wide pressure (see [pressureOfDevice]), or [Pressure.Normal] when the system will not
     * say. Graphics and native memory do not show in the Java heap numbers.
     */
    private fun devicePressure(): Pressure = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@runCatching Pressure.Normal
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val cache = imageLoader.memoryCache
        pressureOfDevice(
            info.availMem, info.threshold, info.lowMemory,
            cache?.size?.toLong() ?: 0L, cache?.maxSize?.toLong() ?: 0L
        )
    }.getOrDefault(Pressure.Normal)

    /** Android is asking for memory back (see [pressureOfSystemTrim]). */
    suspend fun onSystemTrim(level: Int) {
        val pressure = pressureOfSystemTrim(level) ?: return
        withContext(Dispatchers.Main) { trimAll(pressure) }
        lastActedAtMs = SystemClock.uptimeMillis()
    }

    /**
     * One sample: the Java heap ([usedBytes] of [maxBytes]) and the device's own pressure
     * ([device], whatever it says about graphics and native memory). Returns the pressure to act
     * on now, or [Pressure.Normal] for none (memory is fine, High has not lasted two samples, or a
     * trim just ran).
     */
    internal fun onSample(
        usedBytes: Long,
        maxBytes: Long,
        nowMs: Long,
        device: Pressure = Pressure.Normal
    ): Pressure {
        val heap = pressureOf(usedBytes, maxBytes)
        val pressure = worse(heap, device)
        if (pressure == Pressure.Normal) {
            highSamples = 0
            return Pressure.Normal
        }
        highSamples++
        // The system reclaims memory slowly and a trim of an already-emptied cache gains nothing,
        // so a Critical that only the device reports waits longer before the next one than a heap
        // Critical, which a collection settles within seconds.
        val cooldown = when {
            pressure != Pressure.Critical -> HIGH_COOLDOWN_MS
            heap == Pressure.Critical -> CRITICAL_COOLDOWN_MS
            else -> DEVICE_CRITICAL_COOLDOWN_MS
        }
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
        const val DEVICE_CRITICAL_COOLDOWN_MS = 30_000L
    }
}
