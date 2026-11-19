package com.hereliesaz.illumera.data.memory

import android.content.ComponentCallbacks2
import android.content.Context
import coil.ImageLoader
import coil.memory.MemoryCache
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryMonitorTest {
    private val cache = mockk<MemoryCache>(relaxed = true)
    private val loader = mockk<ImageLoader>(relaxed = true).also {
        io.mockk.every { it.memoryCache } returns cache
    }
    private val monitor = MemoryMonitor(loader, mockk<Context>(relaxed = true))
    private val max = 1_000L

    @Test fun pressureFollowsTheShareOfTheHeapInUse() {
        assertEquals(Pressure.Normal, pressureOf(600, max))
        assertEquals(Pressure.High, pressureOf(700, max))
        assertEquals(Pressure.High, pressureOf(849, max))
        assertEquals(Pressure.Critical, pressureOf(850, max))
        assertEquals(Pressure.Normal, pressureOf(900, 0))
    }

    @Test fun trimmersAreAskedLeastUsefulFirst() {
        val calls = mutableListOf<String>()
        monitor.register(MemoryRank.HOME_DATA) { calls += "data" }
        monitor.register(MemoryRank.SPECULATIVE_PREVIEW) { calls += "preview" }
        monitor.register(MemoryRank.IMAGES) { calls += "images" }
        monitor.trimAll(Pressure.High)
        // The image cache registered itself at rank IMAGES; the order is by rank, not by when.
        assertEquals(listOf("preview", "images", "data"), calls)
    }

    @Test fun aClosedTrimmerIsNotAskedAgain() {
        var calls = 0
        val registration = monitor.register(MemoryRank.HOME_DATA) { calls++ }
        monitor.trimAll(Pressure.High)
        registration.close()
        monitor.trimAll(Pressure.High)
        assertEquals(1, calls)
    }

    @Test fun aFailingTrimmerDoesNotStopTheRest() {
        var reached = false
        monitor.register(MemoryRank.SPECULATIVE_PREVIEW) { error("boom") }
        monitor.register(MemoryRank.HOME_DATA) { reached = true }
        monitor.trimAll(Pressure.Critical)
        assertEquals(true, reached)
    }

    @Test fun theImageCacheGivesHalfAtHighAndAllAtCritical() {
        monitor.trimAll(Pressure.High)
        verify { cache.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        monitor.trimAll(Pressure.Critical)
        verify { cache.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
    }

    @Test fun highNeedsTwoSamplesInARow() {
        assertEquals(Pressure.Normal, monitor.onSample(750, max, 100_000))
        assertEquals(Pressure.High, monitor.onSample(750, max, 103_000))
    }

    @Test fun aRecoveredSampleResetsTheStreak() {
        monitor.onSample(750, max, 100_000)
        monitor.onSample(300, max, 103_000)
        assertEquals(Pressure.Normal, monitor.onSample(750, max, 106_000))
    }

    @Test fun criticalActsAtOnceThenWaitsOutItsCooldown() {
        assertEquals(Pressure.Critical, monitor.onSample(900, max, 100_000))
        assertEquals(Pressure.Normal, monitor.onSample(900, max, 102_000))
        assertEquals(Pressure.Critical, monitor.onSample(900, max, 105_000))
    }

    @Test fun highWaitsOutALongerCooldown() {
        monitor.onSample(750, max, 100_000)
        assertEquals(Pressure.High, monitor.onSample(750, max, 103_000))
        monitor.onSample(750, max, 106_000)
        assertEquals(Pressure.Normal, monitor.onSample(750, max, 110_000))
        assertEquals(Pressure.High, monitor.onSample(750, max, 118_000))
    }

    @Test fun androidsOwnSignalMapsToPressure() {
        assertNull(pressureOfSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(Pressure.High, pressureOfSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(Pressure.Critical, pressureOfSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(Pressure.High, pressureOfSystemTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(Pressure.Critical, pressureOfSystemTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    private val mb = 1024L * 1024

    @Test fun theDeviceIsCriticalWhenTheSystemSaysLowOrAvailIsAtItsThreshold() {
        assertEquals(Pressure.Critical, pressureOfDevice(2_000 * mb, 200 * mb, lowMemory = true))
        assertEquals(Pressure.Critical, pressureOfDevice(200 * mb, 200 * mb, lowMemory = false))
        assertEquals(Pressure.Critical, pressureOfDevice(150 * mb, 200 * mb, lowMemory = false))
    }

    @Test fun theDeviceIsHighWhenAvailIsClosingInOnTheThreshold() {
        assertEquals(Pressure.High, pressureOfDevice(300 * mb, 200 * mb, lowMemory = false))
        assertEquals(Pressure.Normal, pressureOfDevice(301 * mb, 200 * mb, lowMemory = false))
        // 2.6 GB free against a 200 MB threshold is plenty.
        assertEquals(Pressure.Normal, pressureOfDevice(2_600 * mb, 200 * mb, lowMemory = false))
    }

    @Test fun aFullImageCacheMakesNarrowingHeadroomHigh() {
        // 500 MB free is 2.5x the threshold: fine with an empty cache, High with a full one.
        assertEquals(Pressure.Normal, pressureOfDevice(500 * mb, 200 * mb, false, 5 * mb, 40 * mb))
        assertEquals(Pressure.High, pressureOfDevice(500 * mb, 200 * mb, false, 33 * mb, 40 * mb))
        // ...but not with plenty of headroom, or when there is no cache to read.
        assertEquals(Pressure.Normal, pressureOfDevice(700 * mb, 200 * mb, false, 39 * mb, 40 * mb))
        assertEquals(Pressure.Normal, pressureOfDevice(500 * mb, 200 * mb, false, 0, 0))
    }

    @Test fun anUnreportedThresholdIsNoPressure() {
        assertEquals(Pressure.Normal, pressureOfDevice(10 * mb, 0, lowMemory = false))
    }

    @Test fun theWorseOfHeapAndDeviceWins() {
        // Heap fine (50%), device critical: act at once.
        assertEquals(Pressure.Critical, monitor.onSample(500, max, 100_000, device = Pressure.Critical))
    }

    @Test fun aDeviceOnlyCriticalWaitsLongerThanAHeapCritical() {
        assertEquals(Pressure.Critical, monitor.onSample(500, max, 100_000, device = Pressure.Critical))
        // A heap Critical would act again after 5 s; the device's own waits 30 s.
        assertEquals(Pressure.Normal, monitor.onSample(500, max, 106_000, device = Pressure.Critical))
        assertEquals(Pressure.Normal, monitor.onSample(500, max, 129_000, device = Pressure.Critical))
        assertEquals(Pressure.Critical, monitor.onSample(500, max, 131_000, device = Pressure.Critical))
    }

    @Test fun aDeviceHighNeedsTwoSamplesLikeAHeapHigh() {
        assertEquals(Pressure.Normal, monitor.onSample(500, max, 100_000, device = Pressure.High))
        assertEquals(Pressure.High, monitor.onSample(500, max, 103_000, device = Pressure.High))
    }

    @Test fun theHeapStillDecidesOnItsOwnWhenTheDeviceIsFine() {
        assertEquals(Pressure.Critical, monitor.onSample(900, max, 100_000, device = Pressure.Normal))
    }

    @Test fun theImageCacheKeepsItsDefaultOnADeviceWithRamForIt() {
        val default = 77 * mb
        assertEquals(default, imageCacheLimitBytes(default, 4_000 * mb))
        // 2.6 GB (the reported TV): 3.5% is 91 MB, above the 77 MB default.
        assertEquals(default, imageCacheLimitBytes(default, 2_600 * mb))
    }

    @Test fun theImageCacheIsHeldToAShareOfRamOnASmallDevice() {
        assertEquals((1_024 * mb * 0.035).toLong(), imageCacheLimitBytes(77 * mb, 1_024 * mb))
        // Never below the floor, and never above the default.
        assertEquals(24 * mb, imageCacheLimitBytes(77 * mb, 512 * mb))
        assertEquals(20 * mb, imageCacheLimitBytes(20 * mb, 512 * mb))
        assertEquals(77 * mb, imageCacheLimitBytes(77 * mb, 0))
    }
}
