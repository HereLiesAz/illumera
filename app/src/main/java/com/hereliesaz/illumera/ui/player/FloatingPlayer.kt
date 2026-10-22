package com.hereliesaz.illumera.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceView
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The app's own picture-in-picture for TV boxes without the system one: on leaving the app
 * while a video plays, the video keeps playing in a small window over other apps (needs
 * "Display over other apps"). The window takes no input, so the remote stays with whatever
 * app is in front; reopening Illumera brings the video back full screen. It closes itself when
 * playback stops, ends, or another app takes audio focus (which also keeps two videos from
 * competing for the box's hardware decoders).
 */
object FloatingPlayer {
    private const val PREFS = "illumera_display"
    private const val KEY_ENABLED = "floating_player"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Opens the "Display over other apps" page; returns false if the box has none. */
    fun requestPermission(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )
        return intents.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }

    /** Whether leaving the app should hand the video to the floating window. */
    fun shouldFloat(context: Context): Boolean =
        isEnabled(context) && hasPermission(context) && !context.supportsPictureInPicture()

    private var player: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private var onClosed: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val stopped = !player.playWhenReady ||
                player.playbackState == Player.STATE_ENDED ||
                player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
            if (stopped) surface?.let { hide(it.context) }
        }
    }

    /**
     * Moves [player]'s video into the floating window; [onClosed] runs when the window goes.
     * No foreground service: Play would require a declaration for one, and a visible overlay
     * already keeps the process at perceptible priority.
     */
    fun show(context: Context, player: ExoPlayer, onClosed: () -> Unit) {
        val app = context.applicationContext
        hide(app)
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screen = app.resources.displayMetrics
        val width = maxOf(screen.widthPixels, screen.heightPixels) / 3
        val margin = width / 12
        val view = SurfaceView(app)
        val params = WindowManager.LayoutParams(
            width, width * 9 / 16,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = margin
            y = margin
        }
        if (runCatching { windowManager.addView(view, params) }.isFailure) {
            player.pause()
            return
        }
        this.player = player
        this.surface = view
        this.onClosed = onClosed
        player.setVideoSurfaceView(view)
        player.addListener(listener)
    }

    /** Closes the floating window, if any, and hands the video back via its onClosed. */
    fun hide(context: Context) {
        val app = context.applicationContext
        val view = surface
        val held = player
        val closed = onClosed
        surface = null
        player = null
        onClosed = null
        held?.removeListener(listener)
        if (view != null) {
            held?.clearVideoSurfaceView(view)
            runCatching { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
        }
        closed?.invoke()
    }
}
