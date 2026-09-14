package com.hereliesaz.illumera.ui.profiles

import java.net.URI

internal fun resolveSetupAccountAvatarRef(
    enabled: Boolean,
    stremioAvatarUrl: String?
): String? {
    if (!enabled) return null
    val url = stremioAvatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
    if (scheme != "http" && scheme != "https") return null
    return ProfileAssets.urlAvatarRef(url)
}
