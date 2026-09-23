package com.hereliesaz.illumera.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingTest {
    private val trace = """
        ----- pid 4242 at 2026-09-23 18:30:01.123 -----
        Cmd line: com.hereliesaz.illumera

        "Signal Catcher" daemon prio=10 tid=2 Runnable
          at java.lang.Object.wait(Native method)

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a1 self=0xb400
          at com.hereliesaz.illumera.ui.details.DetailsViewModel.openEpisodes(DetailsViewModel.kt:675)
          - waiting to lock <0x0a1b> (a java.lang.Object) held by thread 17
          at android.os.MessageQueue.nativePollOnce(Native method)
          at android.os.Looper.loop(Looper.java:288)

        "RenderThread" prio=7 tid=20 Native
          at android.os.SomethingElse.run(SomethingElse.java:1)
    """.trimIndent()

    @Test
    fun parsesOnlyTheMainThreadFrames() {
        val frames = CrashReporting.parseMainThread(trace)

        assertEquals(3, frames.size)
        assertEquals("com.hereliesaz.illumera.ui.details.DetailsViewModel", frames[0].className)
        assertEquals("openEpisodes", frames[0].methodName)
        assertEquals("DetailsViewModel.kt", frames[0].fileName)
        assertEquals(675, frames[0].lineNumber)
        assertTrue(frames[1].isNativeMethod)
        assertEquals(288, frames[2].lineNumber)
    }

    @Test
    fun missingMainThreadYieldsEmptyStack() {
        assertEquals(0, CrashReporting.parseMainThread("no threads here").size)
    }
}
