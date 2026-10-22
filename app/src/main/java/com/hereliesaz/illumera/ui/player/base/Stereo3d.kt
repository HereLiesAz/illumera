package com.hereliesaz.illumera.ui.player.base

import android.content.Context

/** How a frame packs two eyes; [OFF] plays the frame as-is. */
enum class StereoLayout { OFF, SIDE_BY_SIDE, TOP_BOTTOM }

/**
 * Frame-packed 3D handling.
 *
 * Android exposes no API that reports whether the attached TV can show 3D (HDMI 3D
 * capability never reaches apps), so the display is assumed 2D unless the viewer says
 * otherwise (Settings → Playback → Disable 3D off). On a 2D display a 3D source shows both eyes squeezed
 * together; "Play in 2D" shows one eye at the correct aspect instead. Blu-ray 3D (MVC)
 * is reduced to its base view automatically on such a display (MvcStripExtractors).
 */
object Stereo3d {
    private const val PREFS = "illumera_display"
    private const val KEY_TV_SUPPORTS_3D = "tv_supports_3d"

    private val topBottom = Regex("""(?i)\b(h-?ou|half.?ou|h-?tab|half.?tab|top.?and.?bottom|over.?under)\b""")
    // Bare OU/TAB are ordinary words ("ou" in French titles); they count only beside "3D".
    private val shortTopBottom = Regex("""(?i)\b(ou|tab)\b""")
    private val sideBySide = Regex("""(?i)\b(h-?sbs|half.?sbs|f?sbs|side.?by.?side)\b""")
    private val generic3d = Regex("""(?i)\b3d\b""")

    /**
     * The frame packing named by a stream's title/filename. A bare "3D" names no packing
     * and is usually Blu-ray 3D (MVC), whose single frames need no cropping; MVC is handled
     * at extraction instead (MvcStripExtractors), so it is not reported here.
     */
    fun detect(vararg texts: String?): StereoLayout {
        val text = texts.filterNotNull().joinToString(" ")
        return when {
            topBottom.containsMatchIn(text) -> StereoLayout.TOP_BOTTOM
            sideBySide.containsMatchIn(text) -> StereoLayout.SIDE_BY_SIDE
            generic3d.containsMatchIn(text) && shortTopBottom.containsMatchIn(text) -> StereoLayout.TOP_BOTTOM
            else -> StereoLayout.OFF
        }
    }

    fun displaySupports3d(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TV_SUPPORTS_3D, false)

    fun setDisplaySupports3d(context: Context, supported: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_TV_SUPPORTS_3D, supported).apply()
    }

    /**
     * Displayed aspect of one eye. Full side-by-side frames are about twice as wide as
     * 16:9 (3840×1080) and full top-bottom about twice as tall; half-resolution packings
     * keep the 16:9 frame and squeeze each eye, so one eye stretched back is the frame's aspect.
     */
    fun eyeAspect(layout: StereoLayout, frameAspect: Float): Float = when (layout) {
        StereoLayout.SIDE_BY_SIDE -> if (frameAspect >= 2.5f) frameAspect / 2f else frameAspect
        StereoLayout.TOP_BOTTOM -> if (frameAspect <= 1.2f) frameAspect * 2f else frameAspect
        StereoLayout.OFF -> frameAspect
    }
}
