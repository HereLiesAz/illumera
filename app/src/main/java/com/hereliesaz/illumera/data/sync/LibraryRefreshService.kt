package com.hereliesaz.illumera.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.auth.StremioLibrarySyncManager
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.trakt.TraktAuthManager
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * User-visible, user-triggered foreground refresh for Illumera's connected
 * library data. This is intentionally a dataSync foreground service: it can
 * continue the requested network refresh while the user navigates away from
 * Settings, and it always presents an ongoing notification with a cancel
 * action while work is active.
 */
@AndroidEntryPoint
class LibraryRefreshService : Service() {

    @Inject lateinit var stremioAuthManager: StremioAuthManager
    @Inject lateinit var stremioLibrarySyncManager: StremioLibrarySyncManager
    @Inject lateinit var addonRepository: AddonRepository
    @Inject lateinit var profileConfigurationManager: ProfileConfigurationManager
    @Inject lateinit var traktAuthManager: TraktAuthManager
    @Inject lateinit var traktSyncManager: TraktSyncManager

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var refreshJob: Job? = null

    companion object {
        private const val TAG = "LibraryRefreshService"
        private const val CHANNEL_ID = "library_refresh"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_REFRESH = "com.hereliesaz.illumera.action.REFRESH_LIBRARY"
        private const val ACTION_CANCEL = "com.hereliesaz.illumera.action.CANCEL_LIBRARY_REFRESH"

        fun start(context: Context) {
            val intent = Intent(context, LibraryRefreshService::class.java)
                .setAction(ACTION_REFRESH)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            refreshJob?.cancel()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat("Preparing library refresh…")

        if (refreshJob?.isActive != true) {
            refreshJob = scope.launch {
                try {
                    refreshConnectedData()
                    updateNotification("Library refresh complete")
                } catch (e: CancellationException) {
                    Log.i(TAG, "Library refresh cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Library refresh failed", e)
                    updateNotification("Library refresh finished with errors")
                } finally {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun refreshConnectedData() {
        // Stremio Continue Watching is a true two-way network sync.
        if (stremioAuthManager.getStoredAuthKey() != null) {
            updateNotification("Syncing Stremio library…")
            stremioLibrarySyncManager.syncLibrary(force = true)

            // Refresh the account's addon collection from Stremio so the
            // connected-library state is checked as part of this explicit sync.
            updateNotification("Refreshing Stremio addon data…")
            stremioAuthManager.fetchAddons()
        }

        // Refresh manifests for addons already installed on this profile. The
        // repository preserves existing user customizations; newly discovered
        // catalog entries stay hidden by default during a refresh.
        updateNotification("Refreshing installed addons…")
        val installedAddons = addonRepository.getAddons().firstOrNull().orEmpty()
        for (addon in installedAddons) {
            val manifestUrl = "${addon.transportUrl.trimEnd('/')}/manifest.json"
            runCatching {
                addonRepository.installAddonWithConfig(
                    url = manifestUrl,
                    home = false,
                    movies = false,
                    series = false
                )
            }.onFailure { error ->
                Log.w(TAG, "Could not refresh addon ${addon.name}: ${error.message}")
            }
        }
        if (installedAddons.isNotEmpty()) {
            runCatching { profileConfigurationManager.saveActiveRuntimeState() }
                .onFailure { Log.w(TAG, "Could not persist refreshed addon state", it) }
            profileConfigurationManager.resetStartupCapture()
        }

        // Trakt refreshes are only attempted when the user connected Trakt.
        if (traktAuthManager.getAccessToken() != null) {
            updateNotification("Syncing Trakt watchlist…")
            traktSyncManager.syncWatchlist()

            updateNotification("Syncing playback progress…")
            traktSyncManager.syncPlaybackProgress()

            updateNotification("Syncing watched history…")
            traktSyncManager.syncSeriesNextUp()
        }
    }

    private fun startForegroundCompat(text: String) {
        ensureNotificationChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val cancelIntent = Intent(this, LibraryRefreshService::class.java)
            .setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Illumera library refresh")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Library refresh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while Illumera refreshes connected library data"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
