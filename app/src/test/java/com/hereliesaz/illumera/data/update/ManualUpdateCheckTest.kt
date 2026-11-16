package com.hereliesaz.illumera.data.update

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The About screen's Check for Update, for the GitHub build (debug builds are not Play builds). */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualUpdateCheckTest {
    private fun checkWith(state: UpdateState): Pair<ManualUpdateCheck, AppUpdateManager> {
        val github = mockk<AppUpdateManager>()
        every { github.state } returns MutableStateFlow(state)
        coEvery { github.checkForUpdate(any()) } returns Unit
        return ManualUpdateCheck(github, mockk(relaxed = true)) to github
    }

    private suspend fun kotlinx.coroutines.test.TestScope.resultOf(state: UpdateState): List<ManualUpdateResult> {
        val (check, _) = checkWith(state)
        val results = mutableListOf<ManualUpdateResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { check.results.toList(results) }
        check.run()
        return results
    }

    @Test
    fun anUpdateIsReportedAsAvailable() = runTest {
        val info = UpdateInfo("9.9.9.9", "notes", "https://github.com/x/y/releases/download/v/app.apk")
        assertEquals(listOf<ManualUpdateResult>(ManualUpdateResult.Available), resultOf(UpdateState.UpdateAvailable(info)))
    }

    @Test
    fun upToDateReportsNoUpdateWithoutADetail() = runTest {
        assertEquals(listOf<ManualUpdateResult>(ManualUpdateResult.NoUpdate()), resultOf(UpdateState.UpToDate))
    }

    @Test
    fun aFailedCheckReportsNoUpdateWithItsMessage() = runTest {
        assertEquals(
            listOf<ManualUpdateResult>(ManualUpdateResult.NoUpdate("Update check failed: HTTP 403")),
            resultOf(UpdateState.Error("Update check failed: HTTP 403"))
        )
    }

    @Test
    fun aDownloadAlreadyUnderWayReportsNothing() = runTest {
        assertTrue(resultOf(UpdateState.Downloading(0.5f)).isEmpty())
    }

    @Test
    fun checkingFlagIsClearedWhenTheCheckEnds() = runTest {
        val (check, _) = checkWith(UpdateState.UpToDate)
        check.run()
        assertEquals(false, check.checking.value)
    }
}
