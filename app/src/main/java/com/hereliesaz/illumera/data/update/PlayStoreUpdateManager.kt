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
import com.google.gson.JsonParser
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
    /** Play owns installation even if a newer upstream version is announced first. */
    data class UpdateAvailable(
        val versionCode: Int,
        val fromPlay: Boolean = true,
        val announcedVersion: String? = null
    ) : PlayStoreUpdateState()
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
    private var checkGeneration = 0L
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
        val generation = ++checkGeneration

        _state.value = PlayStoreUpdateState.Checking
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (generation != checkGeneration) return@addOnSuccessListener
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
                if (_state.value == PlayStoreUpdateState.UpToDate) checkPublishedVersion(PlayStoreUpdateState.UpToDate, generation)
            }
            .addOnFailureListener { error ->
                if (generation != checkGeneration) return@addOnFailureListener
                if (_state.value is PlayStoreUpdateState.InProgress) return@addOnFailureListener
                pendingInfo = null
                checkPublishedVersion(
                    PlayStoreUpdateState.Error(error.message ?: "Could not check Google Play for updates."),
                    generation
                )
            }
    }

    /**
     * Play Core only supports phones, tablets and ChromeOS. On TV and when
     * Play Core has not caught up, the repository's Play publication marker
     * and latest release announcement may still reveal a newer build.
     * These signals never authorize sideloading a GitHub APK into a Play install.
     */
    private fun checkPublishedVersion(otherwise: PlayStoreUpdateState, generation: Long) {
        scope.launch {
            val published = runCatching { fetchPublishedVersionCode() }.getOrNull()
            val announced = if (published != null && published > BuildConfig.VERSION_CODE) {
                null
            } else runCatching { fetchAnnouncedVersion() }.getOrNull()
            if (generation != checkGeneration || _state.value is PlayStoreUpdateState.InProgress) {
                return@launch
            }
            _state.value = when {
                published != null && published > BuildConfig.VERSION_CODE ->
                    PlayStoreUpdateState.UpdateAvailable(published, fromPlay = false)
                announced != null && AppUpdateManager.isVersionNewer(announced, BuildConfig.VERSION_NAME) ->
                    PlayStoreUpdateState.UpdateAvailable(
                        versionCode = -1,
                        fromPlay = false,
                        announcedVersion = announced
                    )
                else -> otherwise
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

    private fun fetchAnnouncedVersion(): String? {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        okHttpClient.newCall(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JsonParser.parseString(response.body?.string() ?: return null).asJsonObject
            val tag = json.get("tag_name")?.asString ?: return null
            val asset = json.getAsJsonArray("assets")?.firstOrNull { item ->
                item.asJsonObject.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true
            }?.asJsonObject?.get("name")?.asString ?: return null
            return AppUpdateManager.githubReleaseVersion(tag, asset)
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
            if (started) {
                _state.value = PlayStoreUpdateState.InProgress
            } else {
                openPlayStoreListing()
            }
        } catch (_: Exception) {
            openPlayStoreListing()
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
