package com.hereliesaz.illumera.ui.playback

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hereliesaz.illumera.crash.AppErrors
import com.hereliesaz.illumera.data.torrent.TorrentProgress
import com.hereliesaz.illumera.data.torrent.TorrentGate
import com.hereliesaz.illumera.data.torrent.TorrentService
import com.hereliesaz.illumera.data.torrent.TorrentVpnGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Starts and stops [TorrentService]. */
internal interface TorrentServiceLauncher {
    fun start(magnet: String, fileIdx: Int, fileName: String)
    fun stop()
}

/** Where [TorrentService]'s static callbacks live. */
internal interface TorrentCallbackRegistry {
    fun install(
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
        onProgress: (TorrentProgress?) -> Unit
    )

    /** Drops the callbacks, but only while [onReady] is still the installed one. */
    fun clearIfOwned(onReady: (String) -> Unit)
}

private class ContextTorrentServiceLauncher(private val context: Context) : TorrentServiceLauncher {
    override fun start(magnet: String, fileIdx: Int, fileName: String) {
        context.startService(Intent(context, TorrentService::class.java).apply {
            putExtra("MAGNET_LINK", magnet)
            putExtra("FILE_IDX", fileIdx)
            putExtra("FILE_NAME", fileName)
        })
    }

    override fun stop() {
        context.stopService(Intent(context, TorrentService::class.java))
    }
}

private object TorrentServiceCallbacks : TorrentCallbackRegistry {
    override fun install(
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
        onProgress: (TorrentProgress?) -> Unit
    ) {
        TorrentService.onStreamReady = onReady
        TorrentService.onStreamError = onError
        TorrentService.onStreamProgress = onProgress
    }

    override fun clearIfOwned(onReady: (String) -> Unit) {
        if (TorrentService.onStreamReady !== onReady) return
        TorrentService.onStreamReady = null
        TorrentService.onStreamError = null
        TorrentService.onStreamProgress = null
    }
}

/**
 * The one owner of [TorrentService]'s static callbacks. It lives in
 * [PlaybackSessionViewModel], so a stream that becomes ready after the activity is
 * recreated (a phone rotation) still lands in the live session instead of in state
 * that belonged to the destroyed composition.
 */
class TorrentStreamController internal constructor(
    private val launcher: TorrentServiceLauncher,
    private val callbacks: TorrentCallbackRegistry,
    private val gate: TorrentGate = TorrentGate.Open,
    private val notify: (String) -> Unit = {}
) {
    @Inject
    constructor(@ApplicationContext context: Context, guard: TorrentVpnGuard) :
        this(
            ContextTorrentServiceLauncher(context), TorrentServiceCallbacks, guard,
            { message ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        )

    /** The running torrent's progress, or null when no torrent is starting or streaming. */
    var progress by mutableStateOf<TorrentProgress?>(null)

    // Bumped by every start and by release; callbacks from an older start are dropped.
    private var generation = 0L
    private var installedReady: ((String) -> Unit)? = null
    private var endWatch: (() -> Unit)? = null

    /**
     * Fails the torrent (a refusal, or a debrid service that couldn't deliver): stops whatever was running, and reports a failed source the same
     * way the service does, so the player's ranked fallback can move on to a direct source.
     */
    fun fail(
        reason: String,
        errorContext: String,
        onError: (String) -> Unit,
        forceFallback: Boolean = false
    ) {
        endWatch?.invoke()
        endWatch = null
        generation++
        installedReady?.let(callbacks::clearIfOwned)
        installedReady = null
        launcher.stop()
        AppErrors.e("LumeraTorrent", "$errorContext: $reason")
        progress = TorrentProgress(
            status = reason, sourceError = true, attemptId = System.nanoTime(), forceFallback = forceFallback
        )
        notify(reason)
        onError(reason)
    }

    /**
     * Streams [magnet] through [TorrentService] and makes this call the current owner of its
     * callbacks: an earlier start stops delivering.
     */
    fun start(
        magnet: String,
        fileIdx: Int,
        fileName: String,
        errorContext: String = "Stream error",
        clearProgressOnError: Boolean = true,
        onError: (String) -> Unit = {},
        onReady: (String) -> Unit
    ) {
        gate.blockReason()?.let { return fail(it, errorContext, onError) }
        val startGeneration = ++generation
        progress = TorrentProgress("Starting torrent")
        val ready: (String) -> Unit = { localUrl ->
            if (startGeneration == generation) {
                progress = null
                onReady(localUrl)
            }
        }
        val error: (String) -> Unit = { message ->
            if (startGeneration == generation) {
                if (clearProgressOnError) progress = null
                AppErrors.e("LumeraTorrent", "$errorContext: $message")
                onError(message)
            }
        }
        val progressCallback: (TorrentProgress?) -> Unit = { update ->
            if (startGeneration == generation) progress = update
        }
        installedReady = ready
        callbacks.install(ready, error, progressCallback)
        launcher.start(magnet, fileIdx, fileName)
        // The VPN dropping mid-stream stops the torrent too.
        endWatch?.invoke()
        endWatch = gate.watch { reason -> fail(reason, errorContext, onError) }
    }

    /**
     * Stops [TorrentService] and drops its callbacks: a stream that turns ready after the
     * stop must not replace whatever the session opened instead.
     */
    fun stop() {
        endWatch?.invoke()
        endWatch = null
        release()
        progress = null
        launcher.stop()
    }

    /** Detaches from the service's callbacks without stopping it. */
    fun release() {
        generation++
        installedReady?.let(callbacks::clearIfOwned)
        installedReady = null
    }
}
