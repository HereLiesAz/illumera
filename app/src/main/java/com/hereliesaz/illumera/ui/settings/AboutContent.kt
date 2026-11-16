package com.hereliesaz.illumera.ui.settings

import android.content.Context

/**
 * The text behind the About screen: the README intro, the user guide and the change log.
 * The files are copied into the app's assets at build time from the repo documents
 * (see `syncAboutAssets` in app/build.gradle.kts), so there is one copy of each.
 */
object AboutContent {
    private const val README = "about/README.md"
    private const val GUIDE = "about/USER_GUIDE.md"
    private const val WHATS_NEW = "about/WHATS_NEW.txt"

    fun readmeIntro(context: Context): String = read(context, README).let(::introOf)
    fun userGuide(context: Context): String = read(context, GUIDE).let(::plainText)
    fun changeLog(context: Context): String = read(context, WHATS_NEW).trim()

    private fun read(context: Context, path: String): String =
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")

    /**
     * The README's opening paragraphs: everything after the title and before the first image or
     * `##` heading, with Markdown links reduced to their labels.
     */
    internal fun introOf(markdown: String): String {
        val lines = markdown.lines().dropWhile { it.isBlank() || it.startsWith("# ") }
        val intro = lines.takeWhile { !it.startsWith("![") && !it.startsWith("#") }
        return plainText(intro.joinToString("\n")).trim()
    }

    /** Markdown to readable plain text: drops heading marks, emphasis and link targets. */
    internal fun plainText(markdown: String): String = markdown
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?m)^#{1,6}\s+"""), "")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
