package com.hereliesaz.illumera.ui.browser

/** What the in-app browser does with an address a page is about to load. */
internal sealed interface LinkAction {
    /** Load it in the page. */
    data object Load : LinkAction

    /** An addon install link: hand [manifestUrl] (an https address) to the addon installer. */
    data class InstallAddon(val manifestUrl: String) : LinkAction

    /** Not something a page here may open (intent://, file://, market://, and so on). */
    data object Block : LinkAction
}

/**
 * Addon configuration pages end in an Install button that points at `stremio://host/.../manifest.json`
 * (or at the manifest over https). Those are caught, not loaded, so the app can install the addon.
 */
internal fun classifyLink(url: String): LinkAction {
    val address = url.trim()
    val scheme = schemeOf(address)
    return when (scheme) {
        "stremio" -> installFor("https://" + address.substringAfter("://"))
        "stremios" -> installFor("https://" + address.substringAfter("://"))
        "http", "https" ->
            if (address.substringBefore('?').substringBefore('#').endsWith("/manifest.json", ignoreCase = true))
                LinkAction.InstallAddon(address)
            else LinkAction.Load
        "about", "blob", "data" -> LinkAction.Load
        else -> LinkAction.Block
    }
}

private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

private fun schemeOf(address: String): String =
    SCHEME.find(address)?.groupValues?.get(1)?.lowercase().orEmpty()

private fun installFor(https: String): LinkAction =
    if (https.substringBefore('?').endsWith("/manifest.json", ignoreCase = true)) LinkAction.InstallAddon(https)
    else LinkAction.InstallAddon(https.substringBefore('?').trimEnd('/') + "/manifest.json" +
        https.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" })

/** True for an address the browser may be opened on at all. */
internal fun canOpenInBrowser(url: String): Boolean {
    val scheme = schemeOf(url.trim())
    return scheme == "http" || scheme == "https"
}

/** The part of an address worth showing in the toolbar: the host, without `www.`. */
internal fun displayHost(url: String): String =
    url.substringAfter("://", url).substringBefore('/').substringBefore('?').removePrefix("www.")

/** How far the cursor moves per key press: slow for a tap, faster the longer a key is held. */
internal fun cursorStep(repeatCount: Int): Float = when {
    repeatCount < 3 -> 14f
    repeatCount < 8 -> 28f
    else -> 56f
}
