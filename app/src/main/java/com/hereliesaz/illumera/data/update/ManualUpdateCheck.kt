package com.hereliesaz.illumera.data.update

import com.hereliesaz.illumera.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What a viewer-requested update check found. */
sealed class ManualUpdateResult {
    /** An update exists: the update dialog shows, whatever "Later" or the popup setting said before. */
    data object Available : ManualUpdateResult()

    /** Nothing newer. [detail] says so when the check itself could not finish. */
    data class NoUpdate(val detail: String? = null) : ManualUpdateResult()
}

/**
 * The About screen's **Check for Update**: runs the build's own check (GitHub release for a
 * GitHub build, Google Play for a Play build) and reports what it found, so the activity can
 * show the update dialog or a "No new updates" one. Launch-time and foreground checks are
 * unchanged and stay quiet when there is nothing to say.
 */
@Singleton
class ManualUpdateCheck @Inject constructor(
    private val appUpdateManager: AppUpdateManager,
    private val playStoreUpdateManager: PlayStoreUpdateManager
) {
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _results = MutableSharedFlow<ManualUpdateResult>(extraBufferCapacity = 1)
    val results: SharedFlow<ManualUpdateResult> = _results.asSharedFlow()

    suspend fun run() {
        if (!_checking.compareAndSet(expect = false, update = true)) return
        try {
            val result = if (BuildConfig.USE_PLAY_UPDATES) checkPlay() else checkGithub()
            if (result != null) _results.emit(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            _checking.value = false
        }
    }

    private suspend fun checkGithub(): ManualUpdateResult? {
        appUpdateManager.checkForUpdate(force = true)
        return when (val state = appUpdateManager.state.value) {
            is UpdateState.UpdateAvailable -> ManualUpdateResult.Available
            is UpdateState.Error -> ManualUpdateResult.NoUpdate(state.message)
            // A download or install is already under way; its own dialog is showing.
            is UpdateState.Downloading, is UpdateState.ReadyToInstall -> null
            else -> ManualUpdateResult.NoUpdate()
        }
    }

    private suspend fun checkPlay(): ManualUpdateResult? {
        playStoreUpdateManager.checkForUpdate(force = true)
        // Play Core answers asynchronously; on TV the store listing is read after it fails.
        val state = withTimeoutOrNull(PLAY_CHECK_TIMEOUT_MS) {
            playStoreUpdateManager.state.first { it !is PlayStoreUpdateState.Checking }
        } ?: return ManualUpdateResult.NoUpdate("Google Play didn't answer in time.")
        return when (state) {
            is PlayStoreUpdateState.UpdateAvailable -> ManualUpdateResult.Available
            // An update flow is already running; Play shows its own screen.
            PlayStoreUpdateState.InProgress -> null
            // Play Core unsupported (TV) and the listing is not newer: nothing is known to be newer.
            is PlayStoreUpdateState.Error, PlayStoreUpdateState.UpToDate, PlayStoreUpdateState.Idle,
            PlayStoreUpdateState.Checking -> ManualUpdateResult.NoUpdate()
        }
    }

    private companion object {
        const val PLAY_CHECK_TIMEOUT_MS = 20_000L
    }
}
