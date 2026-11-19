package com.hereliesaz.illumera.data.memory

import android.content.ComponentCallbacks2
import android.content.Context
import coil.ImageLoader
import coil.memory.MemoryCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android's own signals (what `LumeraApplication.onTrimMemory` / `onLowMemory` forward). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MemoryMonitorSystemTrimTest {
    private val cache = mockk<MemoryCache>(relaxed = true)
    private val loader = mockk<ImageLoader>(relaxed = true).also { every { it.memoryCache } returns cache }
    private val monitor = MemoryMonitor(loader, mockk<Context>(relaxed = true))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun runningLowAndCriticalTrimTheImageAndHomeCaches() = runTest {
        val home = mutableListOf<Pressure>()
        monitor.register(MemoryRank.HOME_DATA) { home += it }

        monitor.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        monitor.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(listOf(Pressure.High, Pressure.Critical), home)
        verify { cache.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        verify { cache.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
    }

    @Test fun aModerateRunningLevelAsksForNothing() = runTest {
        val home = mutableListOf<Pressure>()
        monitor.register(MemoryRank.HOME_DATA) { home += it }
        monitor.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(emptyList<Pressure>(), home)
    }
}
