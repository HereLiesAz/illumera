package com.hereliesaz.illumera.data.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager as GooglePlayAppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.hereliesaz.illumera.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class PlayStoreUpdateState {
    data object Idle : PlayStoreUpdateState()
    data object Checking : PlayStoreUpdateState()
    /** [fromPlay] false: Play's update API hasn't caught up yet; the button opens the listing. */
    data class UpdateAvailable(val versionCode: Int, val fromPlay: Boolean = true) : PlayStoreUpdateState()
    data object UpToDate : PlayStoreUpdateState()
    data object InProgress : PlayStoreUpdateState()
    data class Error(val message: String) : PlayStoreUpdateState()
}

/**
 * Google Play update coordinator for the Play build.
 *
 * GitHub-distributed builds keep using [AppUpdateManager]; this class is gated
 * by BuildConfig.USE_PLAY_UPDATES and never queries Play from those builds.
 */
@Singleton
class PlayStoreUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var lastCheckAt = 0L
    private val manager: GooglePlayAppUpdateManager by lazy { AppUpdateManagerFactory.create(context) }
    private val _state = MutableStateFlow<PlayStoreUpdateState>(PlayStoreUpdateState.Idle)
    val state = _state.asStateFlow()

    @Volatile
    private var pendingInfo: AppUpdateInfo? = null

    /**
     * Asks Play, then falls back to the version the release workflow recorded as published.
     * Play's update API can lag a new release by hours, and the app (especially on TV) can stay
     * open for days, so this runs on every return to the foreground, at most every
     * [RECHECK_INTERVAL_MS] unless [force] (a launch or the About screen).
     */
    fun checkForUpdate(force: Boolean = true) {
        if (!BuildConfig.USE_PLAY_UPDATES) {
            _state.value = PlayStoreUpdateState.Idle
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < RECHECK_INTERVAL_MS) return
        if (_state.value is PlayStoreUpdateState.InProgress) return
        lastCheckAt = now

        _state.value = PlayStoreUpdateState.Checking
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                pendingInfo = info
                // An update flow started while this check was in flight wins.
                if (_state.value is PlayStoreUpdateState.InProgress) return@addOnSuccessListener
                _state.value = when (info.updateAvailability()) {
                    UpdateAvailability.UPDATE_AVAILABLE ->
                        PlayStoreUpdateState.UpdateAvailable(info.availableVersionCode())
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        PlayStoreUpdateState.InProgress
                    else -> PlayStoreUpdateState.UpToDate
                }
                if (_state.value == PlayStoreUpdateState.UpToDate) checkPublishedVersion(PlayStoreUpdateState.UpToDate)
            }
            .addOnFailureListener { error ->
                if (_state.value is PlayStoreUpdateState.InProgress) return@addOnFailureListener
                pendingInfo = null
                checkPublishedVersion(
                    PlayStoreUpdateState.Error(error.message ?: "Could not check Google Play for updates.")
                )
            }
    }

    /**
     * The release workflow records each published versionCode in version.properties on the
     * default branch. When it is newer than this install, say so even if Play hasn't caught up.
     */
    private fun checkPublishedVersion(otherwise: PlayStoreUpdateState) {
        scope.launch {
            val published = runCatching { fetchPublishedVersionCode() }.getOrNull()
            // The network fetch is slow; don't clobber an update the user started meanwhile.
            if (_state.value is PlayStoreUpdateState.InProgress) return@launch
            _state.value = if (published != null && published > BuildConfig.VERSION_CODE) {
                PlayStoreUpdateState.UpdateAvailable(published, fromPlay = false)
            } else {
                otherwise
            }
        }
    }

    private fun fetchPublishedVersionCode(): Int? {
        val url = "https://raw.githubusercontent.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/main/version.properties"
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string().orEmpty().lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("versionCode=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
        }
    }

    /**
     * Prefer Play's immediate in-app UI. If Play says that flow is not allowed
     * for this release/device, open the app listing so the user still completes
     * the update through Google Play rather than GitHub.
     */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (!BuildConfig.USE_PLAY_UPDATES) return

        val info = pendingInfo
        val current = _state.value
        if (info == null || (current is PlayStoreUpdateState.UpdateAvailable && !current.fromPlay)) {
            openPlayStoreListing()
            return
        }

        if (!info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            openPlayStoreListing()
            return
        }

        try {
            val started = manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            )
            _state.value = if (started) {
                PlayStoreUpdateState.InProgress
            } else {
                PlayStoreUpdateState.Error("Google Play could not start the update.")
            }
        } catch (error: Exception) {
            _state.value = PlayStoreUpdateState.Error(
                error.message ?: "Google Play could not start the update."
            )
        }
    }

    /**
     * Google recommends resuming an immediate update that was already accepted
     * if the activity returns while Play still reports it in progress.
     */
    fun resumeInterruptedUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (!BuildConfig.USE_PLAY_UPDATES) return

        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                pendingInfo = info
                startUpdate(launcher)
            }
        }
    }

    fun onUpdateFlowResult(resultCode: Int) {
        _state.value = if (resultCode == Activity.RESULT_OK) {
            PlayStoreUpdateState.InProgress
        } else {
            // Do not immediately nag again after a cancellation. The next app
            // launch checks Play again and will notify the user if still needed.
            PlayStoreUpdateState.Idle
        }
    }

    private companion object {
        const val RECHECK_INTERVAL_MS = 30 * 60 * 1000L
    }

    fun openPlayStoreListing() {
        val packageName = context.packageName
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(marketIntent) }
            .onFailure { runCatching { context.startActivity(webIntent) } }
    }
}
