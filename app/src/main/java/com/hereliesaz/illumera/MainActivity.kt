package com.hereliesaz.illumera

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hereliesaz.illumera.data.update.AppUpdateManager
import com.hereliesaz.illumera.data.update.PlayStoreUpdateManager
import com.hereliesaz.illumera.data.update.PlayStoreUpdateState
import com.hereliesaz.illumera.data.update.UpdateInfo
import com.hereliesaz.illumera.data.update.UpdateState
import com.hereliesaz.illumera.data.player.PlaybackTrackSelectionStore
import com.hereliesaz.illumera.ui.util.rememberDialogWidth
import com.hereliesaz.illumera.ui.MainViewModel
import com.hereliesaz.illumera.ui.components.LumeraBackground
import com.hereliesaz.illumera.ui.details.DetailsScreen
import com.hereliesaz.illumera.ui.details.DetailsViewModel
import com.hereliesaz.illumera.ui.home.GridViewScreen
import com.hereliesaz.illumera.ui.home.HomeScreen
import com.hereliesaz.illumera.ui.watchlist.WatchlistScreen
import com.hereliesaz.illumera.data.queue.QueueManager
import com.hereliesaz.illumera.ui.home.HomeViewModel
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.data.model.stremio.Stream
import com.hereliesaz.illumera.data.model.stremio.MetaVideo
import com.hereliesaz.illumera.data.repository.IntroRepository
import com.hereliesaz.illumera.domain.AddonSubtitle
import com.hereliesaz.illumera.domain.DashboardTab
import com.hereliesaz.illumera.domain.episodeDisplayTitle
import com.hereliesaz.illumera.ui.navigation.BackStackOps
import com.hereliesaz.illumera.ui.navigation.CastKey
import com.hereliesaz.illumera.ui.navigation.DetailsKey
import com.hereliesaz.illumera.ui.navigation.GridKey
import com.hereliesaz.illumera.ui.navigation.HomeKey
import com.hereliesaz.illumera.ui.navigation.MainRootBackHandler
import com.hereliesaz.illumera.ui.navigation.MainShell
import com.hereliesaz.illumera.ui.navigation.rememberAppBackStack
import com.hereliesaz.illumera.ui.navigation.MoviesKey
import com.hereliesaz.illumera.ui.navigation.SearchKey
import com.hereliesaz.illumera.ui.navigation.SeriesKey
import com.hereliesaz.illumera.ui.navigation.AddonsKey
import com.hereliesaz.illumera.ui.navigation.SettingsKey
import com.hereliesaz.illumera.ui.navigation.WatchlistKey
import com.hereliesaz.illumera.ui.navigation.isMainAreaKey
import com.hereliesaz.illumera.ui.navigation.rememberMainShellState
import com.hereliesaz.illumera.ui.navigation.rememberMenuBackNavEntryDecorator
import com.hereliesaz.illumera.ui.navigation.NavDestination
import com.hereliesaz.illumera.ui.navigation.PlayerKey
import com.hereliesaz.illumera.ui.navigation.StudioKey
import com.hereliesaz.illumera.ui.player.PlayerScreen
import com.hereliesaz.illumera.ui.playback.PlaybackNav
import com.hereliesaz.illumera.ui.playback.PlaybackOrigin
import com.hereliesaz.illumera.ui.playback.PlaybackSessionViewModel
import com.hereliesaz.illumera.ui.playback.buildPlayerSourceOption
import com.hereliesaz.illumera.ui.playback.toPlayerSubtitleSources
import com.hereliesaz.illumera.ui.player.base.NextEpisodeInfo
import com.hereliesaz.illumera.ui.player.base.PlaybackSettings
import com.hereliesaz.illumera.ui.player.base.SkipSegmentInfo
import com.hereliesaz.illumera.ui.profiles.ProfileScreen
import com.hereliesaz.illumera.ui.profiles.ProfileViewModel
import com.hereliesaz.illumera.ui.search.SearchScreen
import com.hereliesaz.illumera.ui.settings.SettingsScreen
import com.hereliesaz.illumera.ui.addons.AddonsScreen
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.theme.DefaultThemes
import com.hereliesaz.illumera.ui.theme.LocalRoundCorners
import com.hereliesaz.illumera.ui.theme.LocalHubRoundCorners
import com.hereliesaz.illumera.ui.theme.LumeraTheme
import com.hereliesaz.illumera.ui.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import kotlinx.coroutines.withContext

import javax.inject.Inject

/** Profile picker only: two Back presses this close together leave the app. */

private fun launchExternalPlayer(context: android.content.Context, url: String) {
    try {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(context, "Unsupported URL scheme", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No external player found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PlayerChoiceDialog(
    onInternal: () -> Unit,
    onExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(480))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Choose Player",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VoidButton(
                        text = "Internal Player",
                        onClick = onInternal,
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                    VoidButton(
                        text = "External Player",
                        onClick = onExternal,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    onDontShowAgain: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val maxDialogHeight = maxHeight * 0.9f

            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(rememberDialogWidth(480))
                    .heightIn(max = maxDialogHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text(
                    "Update Available",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "v${info.versionName}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                if (info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            info.changelog,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(0.7f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VoidButton(
                        text = "Later",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    VoidButton(
                        text = "Update",
                        onClick = onUpdate,
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Don't show again",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onDontShowAgain() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun UpdateDownloadingDialog(progress: Float, downloadedMb: Float, totalMb: Float) {
    Dialog(onDismissRequest = {}) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(480))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Downloading Update",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(0.1f),
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (totalMb > 0f) "%.1f MB / %.1f MB".format(downloadedMb, totalMb)
                    else "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun UpdateErrorDialog(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(480))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Update Failed",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VoidButton(
                        text = "Dismiss",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    VoidButton(
                        text = "Retry",
                        onClick = onRetry,
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateReadyToInstallDialog(
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(480))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Update Ready",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "The update finished downloading. If the install screen didn't open, tap Install.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VoidButton(
                        text = "Dismiss",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    VoidButton(
                        text = "Install",
                        onClick = onInstall,
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayStoreUpdateAvailableDialog(
    fromPlay: Boolean,
    announcedVersion: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(rememberDialogWidth(420))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            androidx.compose.foundation.layout.Column {
                Text(
                    "Update Available",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (fromPlay) {
                        "A newer version of illumera is available from Google Play. Update now to get the latest fixes and features."
                    } else {
                        "A newer version${announcedVersion?.let { " ($it)" }.orEmpty()} has been announced. Check Google Play for availability; installation stays with the Play Store."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VoidButton(
                        text = "Later",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    VoidButton(
                        text = "Update",
                        onClick = onUpdate,
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var playbackTrackSelectionStore: PlaybackTrackSelectionStore
    @Inject
    lateinit var introRepository: IntroRepository
    @Inject
    lateinit var profileConfigurationManager: ProfileConfigurationManager
    @Inject
    lateinit var appUpdateManager: AppUpdateManager
    @Inject
    lateinit var playStoreUpdateManager: PlayStoreUpdateManager
    @Inject
    lateinit var addonDao: AddonDao
    @Inject
    lateinit var queueManager: QueueManager

    private var splashOverlay: android.view.View? = null
    private var splashIndicator: android.view.View? = null
    private var splashMinTimeElapsed = false
    private var splashAppReady = false
    private val _splashFinished = mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        if (::playStoreUpdateManager.isInitialized && BuildConfig.USE_PLAY_UPDATES) {
            playStoreUpdateManager.resumeInterruptedUpdate(playUpdateLauncher)
            // A long-lived session (TV) still hears about a release published since launch.
            playStoreUpdateManager.checkForUpdate(force = false)
        } else if (::appUpdateManager.isInitialized && BuildConfig.ENABLE_SELF_UPDATE) {
            // GitHub installs must be checked again after resuming a long-running session.
            lifecycleScope.launch { appUpdateManager.checkForUpdate(force = false) }
        }
    }

    override fun onStop() {
        super.onStop()
        // NonCancellable: a finishing activity is destroyed right after onStop, which would
        // cancel lifecycleScope mid-save.
        lifecycleScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            profileConfigurationManager.saveActiveRuntimeState()
        }
    }

    override fun onDestroy() {
        splashHandler.removeCallbacksAndMessages(null)
        dismissSplash()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (splashOverlay == null) {
            outState.putBoolean(KEY_SPLASH_SHOWN, true)
        }
    }

    private fun onSplashAppReady() {
        if (splashAppReady) return
        splashAppReady = true
        if (splashMinTimeElapsed) dismissSplash()
    }

    private fun dismissSplash() {
        splashOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        splashOverlay = null
        splashIndicator = null
        _splashFinished.value = true
    }

    private val splashHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun attachSplashOverlay() {
        val handler = splashHandler
        val density = resources.displayMetrics.density

        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo_illumera)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val logoSize = (140 * density).toInt()
        container.addView(logo, android.widget.FrameLayout.LayoutParams(logoSize, logoSize).apply {
            gravity = android.view.Gravity.CENTER
        })

        val indicator = android.widget.ProgressBar(this).apply {
            indeterminateTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            visibility = android.view.View.GONE
        }
        container.addView(indicator, android.widget.FrameLayout.LayoutParams(
            (36 * density).toInt(), (36 * density).toInt()
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (80 * density).toInt()
        })
        splashIndicator = indicator

        handler.postDelayed({
            splashMinTimeElapsed = true
            if (splashAppReady) {
                dismissSplash()
            } else {
                indicator.visibility = android.view.View.VISIBLE
            }
        }, SPLASH_PAUSE_MS.toLong())

        addContentView(container, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        splashOverlay = container
    }

    // Torrent streaming and library refresh run as foreground services; on Android 13+
    // their progress notifications only show once this permission is granted.
    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    private val playUpdateLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (::playStoreUpdateManager.isInitialized) {
                playStoreUpdateManager.onUpdateFlowResult(result.resultCode)
            }
        }

    /** Asks for the notification permission once; the system remembers a refusal. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("permissions", MODE_PRIVATE)
        if (prefs.getBoolean("notifications_asked", false)) return
        prefs.edit().putBoolean("notifications_asked", true).apply()
        notificationPermission.launch(permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sanitize saved state: R8 can obfuscate Parcelable class names, causing
        // BadParcelableException on process-death restore. Clear the bundle if corrupt.
        val safeState = savedInstanceState?.let { bundle ->
            try {
                bundle.keySet() // forces unparcel — throws if any class is missing
                bundle
            } catch (_: android.os.BadParcelableException) {
                null
            }
        }
        super.onCreate(safeState)
        window.setFormat(android.graphics.PixelFormat.RGBA_8888)

        // Fix sideload launch bug: pressing Home and returning re-creates the activity
        // instead of resuming it when the APK was installed via adb/sideload.
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            && Intent.ACTION_MAIN == intent.action) {
            finish()
            return
        }

        requestNotificationPermissionOnce()

        // Default to showing splash; profile's splashEnabled setting is checked async below
        val showSplash = safeState?.getBoolean(KEY_SPLASH_SHOWN) != true
        if (!showSplash) _splashFinished.value = true

        setContent {

            val mainViewModel = hiltViewModel<MainViewModel>()
            val themeManager = hiltViewModel<ThemeManager>()
            val currentProfile by mainViewModel.activeProfile.collectAsState()
            var sessionProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
            var sessionRestoreAttemptedProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
            // The root back stack (a main screen, Grid, Details, Player), saved across process
            // death. A stack saved by an older version that this one can't read starts at Home.
            val backStack = rememberAppBackStack()
            val topKey = backStack.lastOrNull()
            // Playback state lives in the activity-scoped session, so it outlives a
            // configuration change and TorrentService's callbacks always reach it.
            val session = hiltViewModel<PlaybackSessionViewModel>(viewModelStoreOwner = this@MainActivity)
            // A queue start stays pending while the viewer is on the advanced-to title's pages.
            LaunchedEffect(topKey) {
                if (topKey !is DetailsKey && topKey !is CastKey && topKey !is StudioKey && topKey !is com.hereliesaz.illumera.ui.navigation.MoreLikeThisKey && topKey != PlayerKey) {
                    session.queueStartPending = false
                }
            }

            // Debrid library items (Watchlist's cloud storage section) are pre-resolved
            // file URLs with no addon Stream/catalog metadata behind them — this plays
            // one through illumera's own player instead of the hardcoded external
            // ACTION_VIEW intent WatchlistScreen previously used, respecting the same
            // playerPreference (internal/ask/external) as every other playback path.
            val onPlayResolvedStream: (id: String, url: String, title: String) -> Unit = { id, url, title ->
                session.startResolved(id, url, title, currentProfile?.playerPreference)
            }

            // The session asks for navigation and the back stack moves. Collected on
            // Main.immediate so a stack change lands in the same frame as the session change
            // that asked for it.
            LaunchedEffect(session) {
                withContext(Dispatchers.Main.immediate) {
                    session.navEvents.collect { event ->
                        when (event) {
                            PlaybackNav.OpenPlayer -> BackStackOps.openPlayer(backStack)
                            // Pops only the player: Back lands on Details, or on the menu area
                            // for debrid library playback started from Watchlist.
                            is PlaybackNav.ReturnFromPlayer -> BackStackOps.returnFromPlayer(backStack)
                            // On top of the stack: Back returns to the previous show's page, then
                            // to the menu area as the viewer left it. The new page starts
                            // queueAutoPlayId once.
                            is PlaybackNav.OpenDetails -> BackStackOps.queueAdvance(
                                backStack,
                                type = event.movieType,
                                id = event.movieId,
                                title = event.title,
                                poster = event.poster,
                                queueAutoPlayId = event.queueAutoPlayId
                            )
                            is PlaybackNav.LaunchExternal -> launchExternalPlayer(this@MainActivity, event.url)
                            PlaybackNav.ShowPlayerChoice -> session.showPlayerChoiceDialog = true
                        }
                    }
                }
            }

            LaunchedEffect(currentProfile?.id) {
                val profileId = currentProfile?.id
                if (profileId != null) {
                    sessionProfileId = profileId
                    sessionRestoreAttemptedProfileId = null
                }
            }

            LaunchedEffect(currentProfile, sessionProfileId, sessionRestoreAttemptedProfileId) {
                if (currentProfile != null) return@LaunchedEffect
                val profileIdToRestore = sessionProfileId ?: return@LaunchedEffect
                if (sessionRestoreAttemptedProfileId == profileIdToRestore) return@LaunchedEffect

                sessionRestoreAttemptedProfileId = profileIdToRestore
                mainViewModel.login(profileIdToRestore)
            }

            // Resolve theme from profile's themeId
            val currentTheme by themeManager.currentTheme.collectAsState()

            // Get round corners setting from profile (default true)
            val roundCorners = currentProfile?.roundCorners ?: true
            val hubRoundCorners = currentProfile?.hubRoundCorners ?: true
            
            // Update theme when profile changes
            LaunchedEffect(currentProfile) {
                currentProfile?.let { profile ->
                    themeManager.setCurrentProfile(profile.id, profile.themeId)
                }
            }

            // Signal native splash to resume once first composition is done
            LaunchedEffect(Unit) { onSplashAppReady() }

            // Distribution-specific update checks on launch.
            val updateState by appUpdateManager.state.collectAsState()
            val playUpdateState by playStoreUpdateManager.state.collectAsState()
            // Dismiss the currently offered version only; new releases re-open the popup.
            var updateDismissedFor by rememberSaveable { mutableStateOf<String?>(null) }
            // "Later" hides the prompt for that version only; a newer release asks again.
            var playUpdateDismissedFor by rememberSaveable { mutableStateOf<Int?>(null) }
            val updateScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                when {
                    BuildConfig.ENABLE_SELF_UPDATE -> appUpdateManager.checkForUpdate(force = false)
                    BuildConfig.USE_PLAY_UPDATES -> playStoreUpdateManager.checkForUpdate()
                }
            }

            LumeraTheme(theme = currentTheme) {
                CompositionLocalProvider(
                    LocalRoundCorners provides roundCorners,
                    LocalHubRoundCorners provides hubRoundCorners,
                    // "More like this" from any card menu or Details page.
                    com.hereliesaz.illumera.ui.components.LocalOpenMoreLikeThis provides { likeType, likeId, likeTitle ->
                        BackStackOps.openMoreLikeThis(backStack, likeType, likeId, likeTitle)
                    }
                ) {
                LumeraBackground {
                    if (currentProfile == null) {
                        // Same rule as the main screens: Back at a root with nothing left to unwind
                        // exits. The picker has no menu to open first, so one Back exits; its own
                        // dialogs register later and close before this runs.
                        BackHandler { finishAffinity() }

                        val isRestoringSession = sessionProfileId != null
                        if (!isRestoringSession) {
                            // PROFILE SELECTION / CREATION
                            // Always use VOID theme for profile selection (black & white)
                            LumeraTheme(theme = DefaultThemes.VOID) {
                                val profileViewModel = hiltViewModel<ProfileViewModel>()
                                val profiles by profileViewModel.profiles.collectAsState()

                                ProfileScreen(
                                    profiles = profiles,
                                    onProfileSelected = {
                                        sessionProfileId = it.id
                                        sessionRestoreAttemptedProfileId = null
                                        mainViewModel.login(it.id)
                                    }
                                )
                            }
                        }
                    } else {
                        // MAIN APP CONTENT
                        // The main screen is the root at the bottom of the back stack.
                        val currentNav = BackStackOps.currentRoot(backStack).destination
                        val showChrome = isMainAreaKey(topKey)
                        val navPosition = currentProfile?.navPosition ?: "left"

                        // Grid items. Title and config id travel in GridKey; the items stay in
                        // memory here (MetaItem isn't saveable).
                        var gridViewItems by remember { mutableStateOf<List<MetaItem>>(emptyList()) }

                        // Focus Traffic Control
                        val drawerRequesters = remember { NavDestination.entries.associateWith { FocusRequester() } }
                        val homeEntryRequester = remember { FocusRequester() }
                        val searchEntryRequester = remember { FocusRequester() }
                        val settingsEntryRequester = remember { FocusRequester() }
                        val addonsEntryRequester = remember { FocusRequester() }
                        val watchlistEntryRequester = remember { FocusRequester() }
                        val gridEntryRequester = remember { FocusRequester() }

                        val logout: () -> Unit = {
                            sessionProfileId = null
                            sessionRestoreAttemptedProfileId = null
                            BackStackOps.resetToMain(backStack)
                            themeManager.resetTheme()
                            mainViewModel.logout()
                        }

                        // Menu selection. A main screen opens as a fresh entry, also when it is
                        // the one showing: the old entry's state and ViewModels go with it, and
                        // the new screen focuses its own entry point (which closes the menu).
                        // Log Out and Exit are actions.
                        val handleNavigate: (NavDestination) -> Unit = { destination ->
                            when (destination) {
                                NavDestination.Exit -> finishAffinity()
                                NavDestination.Profile -> logout()
                                else -> BackStackOps.navigateToMainRoot(backStack, destination)
                            }
                        }

                        // Closing the menu with nothing to restore focuses the screen's entry
                        // point. runCatching guards against requestFocus() throwing when the
                        // target isn't composed yet.
                        val handleEnterContent: () -> Unit = {
                            runCatching {
                                if (topKey is GridKey) {
                                    gridEntryRequester.requestFocus()
                                } else when (currentNav) {
                                    NavDestination.Home, NavDestination.Movies, NavDestination.Series -> homeEntryRequester.requestFocus()
                                    NavDestination.Search -> searchEntryRequester.requestFocus()
                                    NavDestination.Settings -> settingsEntryRequester.requestFocus()
                                    NavDestination.Addons -> addonsEntryRequester.requestFocus()
                                    NavDestination.Watchlist -> watchlistEntryRequester.requestFocus()
                                    else -> {}
                                }
                            }
                        }

                        // Opens a title's Details from any main screen or the grid.
                        val openDetailsFor: (MetaItem) -> Unit = { movie ->
                            session.clearResumeHint()
                            session.selectedPlaybackId = movie.id
                            session.selectedPlaybackType = movie.type
                            session.selectedPlaybackTitle = movie.name
                            session.selectedPlaybackPoster = movie.poster ?: ""
                            BackStackOps.openDetails(
                                backStack,
                                type = movie.type,
                                id = movie.id,
                                addon = movie.addonBaseUrl,
                                title = movie.name,
                                poster = movie.poster ?: "",
                                logo = movie.logo ?: ""
                            )
                        }

                        // Home, Movies and Series share the activity's HomeViewModel with the
                        // grid (rows keep refreshing; the viewer's place is kept in it).
                        @Composable
                        fun HomeTabEntry(destination: NavDestination) {
                            CompositionLocalProvider(LocalViewModelStoreOwner provides this@MainActivity) {
                                val vm = hiltViewModel<HomeViewModel>()
                                val tab = when (destination) {
                                    NavDestination.Movies -> "movies"
                                    NavDestination.Series -> "series"
                                    else -> "home"
                                }
                                // Chosen in the menu, the screen starts at the top; coming back to
                                // it with Back (saved state restored) keeps the viewer's place.
                                rememberSaveable { vm.forgetPosition(); true }
                                LaunchedEffect(tab, currentProfile?.id) { vm.loadScreen(tab, currentProfile) }
                                HomeScreen(
                                    tab = DashboardTab.fromString(tab),
                                    viewModel = vm,
                                    currentProfile = currentProfile,
                                    entryRequester = homeEntryRequester,
                                    drawerRequester = drawerRequesters.getValue(destination),
                                    onMovieClick = openDetailsFor,
                                    onViewMore = { title, items, configId ->
                                        gridViewItems = items
                                        BackStackOps.openGrid(backStack, title, configId)
                                    }
                                )
                            }
                        }

                        // The NavDisplay keeps one place in the tree; only the menu chrome over it
                        // changes with the top entry and the menu layout. Back with the menu open:
                        // exits when root Back opened it, closes it when Left or a swipe did.
                        val shellState = rememberMainShellState()

                        // A main screen's last Back opens the menu. Registered before the screen
                        // so the screen's own Back steps (keyboard, query, panels) run first.
                        @Composable
                        fun MainRoot(destination: NavDestination, content: @Composable () -> Unit) {
                            MainRootBackHandler(
                                state = shellState,
                                menuRequester = drawerRequesters.getValue(destination),
                                fallbackRequester = drawerRequesters.getValue(NavDestination.Home)
                            )
                            content()
                        }
                        MainShell(
                            navPosition = navPosition,
                            showChrome = showChrome,
                            currentDestination = currentNav,
                            currentProfile = currentProfile,
                            menuRequesters = drawerRequesters,
                            onNavigate = handleNavigate,
                            onEnterContent = handleEnterContent,
                            onLogout = logout,
                            onExit = { finishAffinity() },
                            state = shellState
                        ) {
                        // Back pops one entry. At a main root alone Back is not NavDisplay's: the
                        // screen, then MainRootBackHandler, then the open menu keep it.
                        // No transitions, so a leaving player disposes (saving progress and
                        // releasing the decoder) at once, as before.
                        NavDisplay(
                            backStack = backStack,
                            onBack = { BackStackOps.pop(backStack) },
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                                // An open menu takes Back before the entry's screen does.
                                rememberMenuBackNavEntryDecorator(shellState)
                            ),
                            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            entryProvider = entryProvider {
                            entry<HomeKey> { MainRoot(it.destination) { HomeTabEntry(it.destination) } }
                            entry<MoviesKey> { MainRoot(it.destination) { HomeTabEntry(it.destination) } }
                            entry<SeriesKey> { MainRoot(it.destination) { HomeTabEntry(it.destination) } }
                            entry<SearchKey> { MainRoot(it.destination) {
                                // SearchViewModel belongs to this entry: the query and results
                                // outlive the Details pages opened from them, and choosing Search
                                // in the menu starts a new search. Its FocusMemory (in the entry's
                                // saved state) brings focus back to the poster or "view more" that
                                // opened the page above it.
                                val searchHomeVm = hiltViewModel<HomeViewModel>(viewModelStoreOwner = this@MainActivity)
                                SearchScreen(
                                    currentProfile = currentProfile,
                                    watchedIds = searchHomeVm.state.collectAsState().value.watchedIds,
                                    onMovieClick = openDetailsFor,
                                    onViewMore = { title, items ->
                                        gridViewItems = items
                                        BackStackOps.openGrid(backStack, title, "")
                                    },
                                    entryRequester = searchEntryRequester,
                                    drawerRequester = drawerRequesters.getValue(NavDestination.Search)
                                )
                            } }
                            entry<WatchlistKey> { MainRoot(it.destination) {
                                // WatchlistViewModel and DebridLibraryViewModel belong to this entry.
                                val watchlistHomeVm = hiltViewModel<HomeViewModel>(viewModelStoreOwner = this@MainActivity)
                                WatchlistScreen(
                                    currentProfile = currentProfile,
                                    entryRequester = watchlistEntryRequester,
                                    drawerRequester = drawerRequesters.getValue(NavDestination.Watchlist),
                                    watchedIds = watchlistHomeVm.state.collectAsState().value.watchedIds,
                                    onMovieClick = openDetailsFor
                                )
                            } }
                            entry<AddonsKey> { MainRoot(it.destination) {
                                AddonsScreen(
                                    entryRequester = addonsEntryRequester,
                                    isTopNav = navPosition == "top"
                                )
                            } }
                            entry<SettingsKey> { MainRoot(it.destination) {
                                // Settings keeps the activity's ViewModels: its theme pages share
                                // the activity's ThemeManager, which holds the live theme.
                                CompositionLocalProvider(LocalViewModelStoreOwner provides this@MainActivity) {
                                    val homeVm = hiltViewModel<HomeViewModel>()
                                    SettingsScreen(
                                        currentProfile = currentProfile,
                                        onBack = {
                                            // Leaving Settings is a navigation, not a menu
                                            // open: Home focuses its own content once loaded.
                                            BackStackOps.navigateToMainRoot(backStack, NavDestination.Home)
                                        },
                                        entryRequester = settingsEntryRequester,
                                        drawerRequester = drawerRequesters.getValue(NavDestination.Settings),
                                        onDashboardChanged = { homeVm.invalidate() },
                                        onPlayResolvedStream = onPlayResolvedStream
                                    )
                                }
                            } }
                            entry<GridKey> { gridKey ->
                            // The grid pages through the activity's HomeViewModel rows.
                            CompositionLocalProvider(LocalViewModelStoreOwner provides this@MainActivity) {
                            val gridViewTitle = gridKey.title
                            val gridViewConfigId = gridKey.configId
                            // gridViewItems isn't saveable (MetaItem isn't Parcelable), so a
                            // process-death recreation restores the GridKey but not the items,
                            // leaving a header with nothing under it. Return to the screen before
                            // the grid rather than show that broken empty screen.
                            LaunchedEffect(Unit) {
                                BackStackOps.dropEmptyGrid(
                                    backStack,
                                    hasItems = gridViewTitle.isEmpty() || gridViewItems.isNotEmpty()
                                )
                            }
                            val gridVm = hiltViewModel<HomeViewModel>()
                            // The grid keeps its place (a saveable grid state and its FocusMemory)
                            // in this entry while Details pages open over it; it goes with the entry.
                            GridViewScreen(
                                title = gridViewTitle,
                                items = gridViewItems,
                                onMovieClick = openDetailsFor,
                                onBack = { BackStackOps.pop(backStack) },
                                onLoadMore = {
                                    if (gridViewConfigId.isNotEmpty()) {
                                        gridVm.loadMoreItems(gridViewConfigId)
                                    }
                                },
                                watchedIds = gridVm.state.collectAsState().value.watchedIds,
                                externalEntryRequester = gridEntryRequester
                            )
                            // Sync gridViewItems when ViewModel state updates (after loadMoreItems)
                            val vmState by gridVm.state.collectAsState()
                            LaunchedEffect(vmState.rows) {
                                if (gridViewConfigId.isNotEmpty()) {
                                    val updatedRow = vmState.rows.find { it.configId == gridViewConfigId }
                                    if (updatedRow != null && updatedRow.items.size > gridViewItems.size) {
                                        gridViewItems = updatedRow.items
                                    }
                                }
                            }
                            }
                            }
                            entry<DetailsKey> { detailsKey ->
                            // Every Details page is its own entry with its own DetailsViewModel
                            // (the entry's ViewModelStore). Cast, studio and recommended titles
                            // are entries pushed on top of it; Back pops them one by one.
                            var resolvedPoster by rememberSaveable { mutableStateOf(detailsKey.poster) }
                            // This page's DetailsViewModel (the entry's store), handed to DetailsScreen.
                            val detailsVm = hiltViewModel<DetailsViewModel>()
                            val openDetails: (String, String) -> Unit = { navType, navId ->
                                BackStackOps.openDetails(backStack, type = navType, id = navId)
                            }
                            // What the player needs from this page: its show id (next-episode and
                            // progress ids), title, art, and where the resume hint goes back to.
                            // The show id is the one the page builds episode playback ids from:
                            // the IMDb id resolved behind a tmdb: key, not the key's own id.
                            fun origin(seriesTitle: String = "", logo: String = "") = PlaybackOrigin(
                                ownerTag = detailsKey.playbackOwnerTag,
                                showId = detailsVm.state.value.streamId(detailsKey.id),
                                title = seriesTitle.ifBlank { detailsKey.title },
                                poster = resolvedPoster,
                                logo = logo.ifBlank { detailsKey.logo }
                            )
                            DetailsScreen(
                                type = detailsKey.type,
                                id = detailsKey.id,
                                addonBaseUrl = detailsKey.addon,
                                // A result for this page only: the hint the session leaves when a
                                // playback this page started ends part-way. DetailsScreen still
                                // ignores a hint for another title.
                                resumePlaybackHint = session.resumeHintFor(detailsKey.playbackOwnerTag),
                                autoSelectSource = currentProfile?.autoSelectSource ?: false,
                                rememberSourceSelection = currentProfile?.rememberSourceSelection ?: true,
                                onPosterResolved = { resolvedPoster = it },
                                onPlayClick = { url, playbackId, playbackType, playbackTitle, seriesTitle, logo, stream, addonSubtitles, availableStreams, episodes ->
                                    session.startFromDetails(
                                        origin = origin(seriesTitle, logo),
                                        url = url,
                                        playbackId = playbackId,
                                        playbackType = playbackType,
                                        playbackTitle = playbackTitle.ifBlank { detailsKey.title },
                                        stream = stream,
                                        addonSubtitles = addonSubtitles,
                                        availableStreams = availableStreams,
                                        episodes = episodes,
                                        playerPreference = currentProfile?.playerPreference,
                                        persistProfileState = mainViewModel::persistActiveProfileState
                                    )
                                },
                                onAddToQueue = { queueManager.add(it) },
                                queueAutoPlayId = detailsKey.queueAutoPlayId,
                                onNavigateToDetails = openDetails,
                                onNavigateToCastDetail = { personId, personName ->
                                    BackStackOps.openCast(backStack, personId, personName)
                                },
                                onNavigateToStudioDetail = { entityId, entityKind, entityName, sourceType ->
                                    BackStackOps.openStudio(backStack, entityId, entityKind, entityName, sourceType)
                                },
                                isTrailerLoading = session.isTrailerLoading,
                                onTrailerClick = { youtubeKey, trailerName ->
                                    session.startTrailer(origin(), youtubeKey, trailerName, detailsKey.type)
                                },
                                viewModel = detailsVm
                            )
                            }
                            entry<CastKey> { castKey ->
                            com.hereliesaz.illumera.ui.cast.CastDetailScreen(
                                personId = castKey.personId,
                                personName = castKey.name,
                                onNavigateToDetails = { navType, navId ->
                                    BackStackOps.openDetails(backStack, type = navType, id = navId)
                                }
                            )
                            }
                            entry<StudioKey> { studioKey ->
                            com.hereliesaz.illumera.ui.studio.StudioDetailScreen(
                                entityId = studioKey.entityId,
                                entityKind = studioKey.kind,
                                entityName = studioKey.name,
                                sourceType = studioKey.sourceType,
                                onNavigateToDetails = { navType, navId ->
                                    BackStackOps.openDetails(backStack, type = navType, id = navId)
                                }
                            )
                            }
                            entry<com.hereliesaz.illumera.ui.navigation.MoreLikeThisKey> { likeKey ->
                            com.hereliesaz.illumera.ui.morelikethis.MoreLikeThisScreen(
                                type = likeKey.type,
                                id = likeKey.id,
                                title = likeKey.title,
                                onNavigateToDetails = { navType, navId ->
                                    BackStackOps.openDetails(backStack, type = navType, id = navId)
                                }
                            )
                            }
                            entry<PlayerKey> {
                            // PlayerViewModel belongs to this entry and is cleared with it; its
                            // scrobbles and progress saves run NonCancellable, so leaving the
                            // player never cuts them off. The session stays on the activity.
                            if (session.selectedVideoUrl.isNotBlank() && session.currentStream == null &&
                                !session.selectedPlaybackId.startsWith("trailer_")
                            ) {
                                // The session's sources, subtitles, episode list and current stream
                                // survive a configuration change but not process death, while
                                // selectedVideoUrl is saved and would otherwise resume a degraded,
                                // silently broken player session. Every legitimate NON-TRAILER playback
                                // start sets currentStream alongside selectedVideoUrl, so seeing one
                                // without the other only happens after process death for normal
                                // playback — pop back to the page that opened the player to re-resolve.
                                // Trailers are exempt: onTrailerClick never sets currentStream (it
                                // has no Stream object, just a resolved YouTube URL), so this guard
                                // would otherwise fire on every legitimate trailer play.
                                LaunchedEffect(Unit) {
                                    session.stopTorrent()
                                    BackStackOps.returnFromPlayer(backStack)
                                }
                            } else if (session.selectedVideoUrl.isBlank() && session.torrentProgress == null) {
                                // torrentProgress is lost with the session on process death even when
                                // a TorrentService download is still running
                                // (its foreground service survives independently). Stop it here so
                                // this recovery path doesn't silently abandon an orphaned download.
                                LaunchedEffect(Unit) {
                                    session.stopTorrent()
                                    BackStackOps.returnFromPlayer(backStack)
                                }
                            } else {
                            val rememberedTrackSelection = remember(session.selectedPlaybackId) {
                                playbackTrackSelectionStore.getSelection(session.selectedPlaybackId)
                            }
                            val playerSources = remember(session.selectedPlayerSources) { session.selectedPlayerSources }
                            val playerSubtitles = remember(session.selectedPlayerSubtitles) {
                                session.selectedPlayerSubtitles.toPlayerSubtitleSources()
                            }

                            // Compute next episode
                            val isSeries = session.isSeriesPlayback
                            val autoplayNext = session.autoplayNextEnabled(currentProfile?.autoplayNextEpisode == true)
                            val queueSingleEpisode = session.isQueueSingleEpisode
                            val nextEpisode = remember(session.selectedPlaybackId, session.playbackSeriesId, session.currentEpisodeList, isSeries, queueSingleEpisode) {
                                session.nextEpisode()
                            }
                            val nextEpisodeInfo = remember(nextEpisode) {
                                nextEpisode?.let { ep ->
                                    NextEpisodeInfo(
                                        title = episodeDisplayTitle(ep),
                                        thumbnail = ep.thumbnail,
                                        seasonNumber = ep.season,
                                        episodeNumber = ep.episode
                                    )
                                }
                            }

                            // Fetch skip intro/outro segments from IntroDB
                            val skipIntroEnabled = currentProfile?.skipIntro == true
                            val needIntroDB = skipIntroEnabled || autoplayNext
                            var skipSegmentInfo by remember { mutableStateOf<SkipSegmentInfo?>(null) }
                            LaunchedEffect(session.selectedPlaybackId, needIntroDB, skipIntroEnabled) {
                                skipSegmentInfo = null
                                if (!needIntroDB) return@LaunchedEffect
                                if (!isSeries || session.selectedPlaybackId.isBlank()) return@LaunchedEffect
                                val parts = session.selectedPlaybackId.split(":")
                                if (parts.size < 3) return@LaunchedEffect
                                val imdbId = parts.dropLast(2).joinToString(":")
                                val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return@LaunchedEffect
                                val episode = parts.last().toIntOrNull() ?: return@LaunchedEffect
                                val response = introRepository.getSegments(imdbId, season, episode)
                                if (response != null) {
                                    skipSegmentInfo = SkipSegmentInfo(
                                        introStartMs = if (skipIntroEnabled) response.intro?.start_ms else null,
                                        introEndMs = if (skipIntroEnabled) response.intro?.end_ms else null,
                                        outroStartMs = response.outro?.start_ms,
                                        outroEndMs = response.outro?.end_ms
                                    )
                                }
                            }

                            PlayerScreen(
                                videoUrl = session.selectedVideoUrl,
                                trailerAudioUrl = session.selectedTrailerAudioUrl.takeIf { it.isNotBlank() },
                                title = session.selectedPlaybackTitle.ifBlank { session.playbackSeriesTitle },
                                seriesTitle = session.playbackSeriesTitle.takeIf {
                                    session.selectedPlaybackType.equals("series", ignoreCase = true)
                                },
                                logoUrl = session.playbackLogo.takeIf { it.isNotBlank() },
                                poster = session.selectedPlaybackPoster,
                                movieId = session.selectedPlaybackId,
                                mediaType = session.selectedPlaybackType,
                                seriesId = session.playbackSeriesId.takeIf {
                                    it.isNotBlank() && (
                                        session.selectedPlaybackType.equals("series", ignoreCase = true) ||
                                            session.selectedPlaybackType.equals("tv", ignoreCase = true) ||
                                            session.selectedPlaybackType.equals("episode", ignoreCase = true)
                                        )
                                },
                                sources = playerSources,
                                subtitles = playerSubtitles,
                                preferredAudioTrackId = rememberedTrackSelection?.audioTrackId,
                                preferredSubtitleTrackId = rememberedTrackSelection?.subtitleTrackId,
                                initialSubtitleDelayMs = rememberedTrackSelection?.subtitleDelayMs ?: 0L,
                                playbackSettings = PlaybackSettings(
                                    tunnelingEnabled = currentProfile?.tunnelingEnabled ?: false,
                                    mapDV7ToHevc = currentProfile?.mapDV7ToHevc ?: false,
                                    decoderPriority = currentProfile?.decoderPriority ?: 1,
                                    frameRateMatching = currentProfile?.frameRateMatching ?: false,
                                    autoplayNextEpisode = autoplayNext,
                                    autoSelectSource = currentProfile?.autoSelectSource ?: false,
                                    autoplayThresholdMode = currentProfile?.autoplayThresholdMode ?: "percentage",
                                    autoplayThresholdPercent = currentProfile?.autoplayThresholdPercent ?: 95,
                                    autoplayThresholdSeconds = currentProfile?.autoplayThresholdSeconds ?: 30,
                                    preferredAudioLanguage = currentProfile?.preferredAudioLanguage ?: "",
                                    preferredAudioLanguageSecondary = currentProfile?.preferredAudioLanguageSecondary ?: "",
                                    preferredSubtitleLanguage = currentProfile?.preferredSubtitleLanguage ?: "",
                                    preferredSubtitleLanguageSecondary = currentProfile?.preferredSubtitleLanguageSecondary ?: "",
                                    sourceAudioLanguageRequirement = currentProfile?.sourceAudioLanguageRequirement ?: "off",
                                    sourceSubtitleLanguageRequirement = currentProfile?.sourceSubtitleLanguageRequirement ?: "off",
                                    subtitleSize = currentProfile?.subtitleSize ?: 100,
                                    subtitleOffset = currentProfile?.subtitleOffset ?: 0,
                                    subtitleTextColor = currentProfile?.subtitleTextColor?.toInt() ?: 0xFFFFFFFF.toInt(),
                                    subtitleBackgroundColor = currentProfile?.subtitleBackgroundColor?.toInt() ?: 0x00000000,
                                    assRendererEnabled = currentProfile?.assRendererEnabled ?: false
                                ),
                                skipSegmentInfo = skipSegmentInfo,
                                nextEpisodeInfo = if (nextEpisode != null) nextEpisodeInfo else null,
                                onAutoplayNextEpisode = if (nextEpisode != null) {
                                    { playerCurrentSourceUrl ->
                                        session.autoplayNextEpisode(nextEpisode, playerCurrentSourceUrl, currentProfile)
                                    }
                                } else null,
                                episodes = session.currentEpisodeList,
                                currentPlaybackId = session.selectedPlaybackId,
                                onEpisodeSelected = if (session.currentEpisodeList.isNotEmpty()) {
                                    { episode, playerCurrentSourceUrl, positionMs, durationMs ->
                                        session.selectEpisode(episode, playerCurrentSourceUrl, currentProfile, positionMs, durationMs)
                                    }
                                } else null,
                                episodeSwitchSources = session.pendingEpisodeSwitch?.let { pending ->
                                    pending.streams
                                        ?.mapNotNull(::buildPlayerSourceOption)
                                        ?.distinctBy { it.id }
                                },
                                isEpisodeSwitchLoading = session.isEpisodeSwitchLoading,
                                episodeSwitchTitle = session.pendingEpisodeSwitch?.playbackTitle,
                                onEpisodeSwitchSourceSelected = session.pendingEpisodeSwitch?.let {
                                    { sourceUrl: String -> session.pickEpisodeSwitchSource(sourceUrl, currentProfile) }
                                },
                                onEpisodeSwitchDismissed = session::dismissEpisodeSwitch,
                                onResolveSourceSubtitles = session::resolveSourceSubtitles,
                                onMagnetSourceSelected = session::selectMagnetSource,
                                torrentProgress = session.torrentProgress,
                                playbackStatus = session.playbackStatus,
                                onFirstFrameRendered = session::onFirstFrame,
                                autoFallbackEnabled = currentProfile?.autoSelectSource == true && currentProfile?.sourceAutoFallback != false,
                                onSuspectSource = { status -> session.onSuspectSource(status, currentProfile) },
                                onSourceChanged = session::onPlayerSourceChanged,
                                onBack = { sessionResult -> session.end(sessionResult, currentProfile) }
                            )
                            }
                            }
                            }
                        ) // NavDisplay end
                        } // MainShell end
                    }

                    // Player choice dialog (shown when playerPreference == "ask")
                    if (session.showPlayerChoiceDialog && session.selectedVideoUrl.isNotBlank()) {
                        PlayerChoiceDialog(
                            onInternal = {
                                session.showPlayerChoiceDialog = false
                                BackStackOps.openPlayer(backStack)
                            },
                            onExternal = {
                                session.showPlayerChoiceDialog = false
                                launchExternalPlayer(this@MainActivity, session.selectedVideoUrl)
                            },
                            onDismiss = {
                                session.showPlayerChoiceDialog = false
                            }
                        )
                    }

                    if (session.showTrailerError) {
                        Dialog(onDismissRequest = { session.showTrailerError = false }) {
                            Box(
                                modifier = Modifier
                                    .width(rememberDialogWidth(380))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.background)
                                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                                    .padding(24.dp)
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Trailer Unavailable",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(24.dp))
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        VoidButton(
                                            text = "Dismiss",
                                            onClick = { session.showTrailerError = false },
                                            isPrimary = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Update dialogs (auto-shown after splash)
                    if (BuildConfig.ENABLE_SELF_UPDATE && _splashFinished.value) {
                        when (val state = updateState) {
                            is UpdateState.UpdateAvailable -> if (
                                appUpdateManager.isPopupEnabled && updateDismissedFor != state.info.versionName
                            ) {
                                UpdateAvailableDialog(
                                    info = state.info,
                                    onUpdate = {
                                        updateScope.launch { appUpdateManager.downloadAndInstall(state.info.apkUrl) }
                                    },
                                    onDismiss = { updateDismissedFor = state.info.versionName },
                                    onDontShowAgain = {
                                        appUpdateManager.setPopupEnabled(false)
                                        updateDismissedFor = state.info.versionName
                                    }
                                )
                            }
                            is UpdateState.Downloading -> {
                                UpdateDownloadingDialog(
                                    progress = state.progress,
                                    downloadedMb = state.downloadedMb,
                                    totalMb = state.totalMb
                                )
                            }
                            is UpdateState.Error -> {
                                UpdateErrorDialog(
                                    message = state.message,
                                    onRetry = {
                                        appUpdateManager.resetState()
                                        updateScope.launch { appUpdateManager.checkForUpdate() }
                                    },
                                    onDismiss = {
                                        appUpdateManager.resetState()
                                        updateDismissedFor = (updateState as? UpdateState.UpdateAvailable)?.info?.versionName
                                    }
                                )
                            }
                            is UpdateState.ReadyToInstall -> {
                                UpdateReadyToInstallDialog(
                                    onInstall = { appUpdateManager.retryInstall() },
                                    onDismiss = {
                                        appUpdateManager.resetState()
                                        updateDismissedFor = (updateState as? UpdateState.UpdateAvailable)?.info?.versionName
                                    }
                                )
                            }
                            else -> {}
                        }
                    }

                    // Play Store builds still notify in-app, but Google Play owns
                    // the actual download/install UX.
                    if (BuildConfig.USE_PLAY_UPDATES && _splashFinished.value) {
                        when (val playState = playUpdateState) {
                            is PlayStoreUpdateState.UpdateAvailable -> if (playUpdateDismissedFor != playState.versionCode) {
                                PlayStoreUpdateAvailableDialog(
                                    fromPlay = playState.fromPlay,
                                    announcedVersion = playState.announcedVersion,
                                    onUpdate = { playStoreUpdateManager.startUpdate(playUpdateLauncher) },
                                    onDismiss = { playUpdateDismissedFor = playState.versionCode }
                                )
                            }
                            else -> Unit
                        }
                    }

                }
                }
            }
        }

        // Attach native splash overlay on top of Compose content — renders immediately
        if (showSplash) {
            attachSplashOverlay()
            // Async: dismiss splash immediately if the active profile has it disabled
            lifecycleScope.launch {
                val splashEnabledInProfile = profileConfigurationManager.getLastActiveProfileId()?.let { id ->
                    withContext(Dispatchers.IO) { addonDao.getProfileById(id) }
                }?.splashEnabled ?: true
                if (!splashEnabledInProfile) {
                    dismissSplash()
                }
            }
        }
    }

    companion object {
        private const val SPLASH_PAUSE_MS = 1200
        private const val KEY_SPLASH_SHOWN = "splash_shown"
    }
}
