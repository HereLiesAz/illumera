package com.hereliesaz.illumera.data.memory

import android.content.ComponentCallbacks2
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
    private val monitor = MemoryMonitor(loader)
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
}
