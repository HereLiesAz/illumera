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
 *
 * Only Google Play decides whether an update exists: Play Core where it works, else the
 * builds the central Play release workflow announced as closed `play-release` issues (and, as a
 * last resort, the version Play's own store listing shows). Nothing about GitHub (a newer release, a version
 * marker) ever raises an update here: it says nothing about what Play has published.
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
     * Asks Play Core, and where Play Core can't answer (Android TV) reads the version Play's
     * store listing shows. The app (especially on TV) can stay open for days, so this runs on
     * every return to the foreground, at most every [RECHECK_INTERVAL_MS] unless [force]
     * (a launch or the About screen).
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
            }
            .addOnFailureListener { error ->
                if (generation != checkGeneration) return@addOnFailureListener
                if (_state.value is PlayStoreUpdateState.InProgress) return@addOnFailureListener
                pendingInfo = null
                // Play Core is unsupported on some devices (Android TV): ask the listing instead.
                checkPlayListing(
                    PlayStoreUpdateState.Error(error.message ?: "Could not check Google Play for updates."),
                    generation
                )
            }
    }

    /**
     * Play Core only supports phones, tablets and ChromeOS. Where it can't answer (Android TV),
     * the central Play release workflow's announcements say what Play has published: one closed
     * `play-release` issue per published build (see [latestPlayRelease]). The public store
     * listing is the last resort, since it only shows the production track. Only Play counts: a
     * newer GitHub release says nothing about Play. Nothing newer, or nothing readable, claims
     * nothing and leaves [otherwise].
     */
    private fun checkPlayListing(otherwise: PlayStoreUpdateState, generation: Long) {
        scope.launch {
            val announced = runCatching { fetchAnnouncedRelease() }.getOrNull()
            val listed = if (announced == null) runCatching { fetchPlayListingVersion() }.getOrNull() else null
            if (generation != checkGeneration || _state.value is PlayStoreUpdateState.InProgress) {
                return@launch
            }
            _state.value = when {
                announced != null && isNewerThanInstalled(announced, BuildConfig.VERSION_CODE) ->
                    PlayStoreUpdateState.UpdateAvailable(
                        versionCode = announced.versionCode, fromPlay = false, announcedVersion = announced.versionName
                    )
                announced == null && listed != null &&
                    AppUpdateManager.isVersionNewer(listed, BuildConfig.VERSION_NAME) ->
                    PlayStoreUpdateState.UpdateAvailable(versionCode = -1, fromPlay = false, announcedVersion = listed)
                // The announcements were read and nothing newer is on Play.
                announced != null -> PlayStoreUpdateState.UpToDate
                else -> otherwise
            }
        }
    }

    /** The newest build the Play release workflow has announced in this repository's issues. */
    private fun fetchAnnouncedRelease(): PlayRelease? {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}" +
            "/issues?labels=$PLAY_RELEASE_LABEL&state=closed&per_page=10&sort=created&direction=desc"
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return latestPlayRelease(response.body.string())
        }
    }

    private fun fetchPlayListingVersion(): String? {
        val request = Request.Builder()
            .url("https://play.google.com/store/apps/details?id=${context.packageName}&hl=en_US")
            .header("User-Agent", LISTING_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return parsePlayListingVersion(response.body.string())
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
        const val LISTING_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
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

// The listing embeds the current version as `[[["0.12.107.1"]],[[[37]],[[[26,"8.0"]]]]]`: the
// version, then the target and minimum SDK.
private val LISTING_VERSION = Regex("""\[\[\["(\d+(?:\.\d+){1,3})"\]\],\[\[\[\d+\]\]""")
private val LISTING_VERSION_LOOSE = Regex("""\[\[\["(\d+(?:\.\d+){1,3})"\]\]""")

/** The version a Google Play store listing page shows, or null when it can't be read with confidence. */
internal fun parsePlayListingVersion(html: String): String? {
    LISTING_VERSION.find(html)?.let { return it.groupValues[1] }
    return LISTING_VERSION_LOOSE.findAll(html).map { it.groupValues[1] }.distinct().toList().singleOrNull()
}
