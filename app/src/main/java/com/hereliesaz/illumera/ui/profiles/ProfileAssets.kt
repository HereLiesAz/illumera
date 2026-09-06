package com.hereliesaz.illumera.ui.profiles

import com.hereliesaz.illumera.R
import java.io.File

object ProfileAssets {
    // Prefix for custom uploaded avatars
    private const val CUSTOM_PREFIX = "custom:"

    // Prefix for a remote avatar URL (e.g. a Stremio account's Facebook photo)
    private const val URL_PREFIX = "url:"

    // We map the "Safe String" to the "Unsafe ID"
    val AVATAR_MAP = mapOf(
        "avatar_1" to R.drawable.avatar_1,
        "avatar_2" to R.drawable.avatar_2,
        "avatar_3" to R.drawable.avatar_3,
        "avatar_4" to R.drawable.avatar_4,
        "avatar_5" to R.drawable.avatar_5,
        "avatar_6" to R.drawable.avatar_6,
        "avatar_7" to R.drawable.avatar_7,
        "avatar_8" to R.drawable.avatar_8,
        "avatar_9" to R.drawable.avatar_9,
        "avatar_10" to R.drawable.avatar_10,
        "avatar_11" to R.drawable.avatar_11,
        "avatar_12" to R.drawable.avatar_12,
        "avatar_13" to R.drawable.avatar_13,
        "avatar_14" to R.drawable.avatar_14,
        "avatar_15" to R.drawable.avatar_15,
        "avatar_16" to R.drawable.avatar_16
    )
    
    /**
     * Check if the avatar reference is a custom uploaded avatar.
     */
    fun isCustomAvatar(avatarRef: String): Boolean {
        return avatarRef.startsWith(CUSTOM_PREFIX)
    }

    /**
     * Wraps a remote image URL (e.g. a Stremio account's Facebook photo) as an
     * avatarRef value.
     */
    fun urlAvatarRef(url: String): String = "$URL_PREFIX$url"

    /** Check if the avatar reference is a remote URL avatar. */
    fun isUrlAvatar(avatarRef: String): Boolean = avatarRef.startsWith(URL_PREFIX)

    private fun getUrlAvatarUrl(avatarRef: String): String = avatarRef.removePrefix(URL_PREFIX)

    /**
     * Get the file path for a custom avatar (strips the "custom:" prefix).
     */
    private fun getCustomAvatarPath(avatarRef: String): String {
        return avatarRef.removePrefix(CUSTOM_PREFIX)
    }
    
    /**
     * Get the File object for a custom avatar. Returns null if file doesn't exist.
     */
    private fun getCustomAvatarFile(avatarRef: String): File? {
        val path = getCustomAvatarPath(avatarRef)
        val file = File(path)
        return if (file.exists()) file else null
    }

    /**
     * The Magic Function: String -> Int (for built-in avatars only)
     * For custom avatars, use isCustomAvatar() and getCustomAvatarPath() instead.
     */
    private fun getAvatarRes(name: String): Int {
        // If it's a custom avatar, return fallback (callers should check isCustomAvatar first)
        if (isCustomAvatar(name)) {
            return R.drawable.avatar_1
        }
        return AVATAR_MAP[name] ?: R.drawable.avatar_1 // Fallback to avatar_1 if missing
    }
    
    /**
     * Get avatar source for AsyncImage - returns either Int (resource ID) or File (for custom).
     * Callers should use this for loading avatars:
     * 
     * val source = ProfileAssets.getAvatarSource(avatarRef)
     * AsyncImage(
     *     model = ImageRequest.Builder(context)
     *         .data(source)
     *         .build(),
     *     ...
     * )
     */
    fun getAvatarSource(avatarRef: String): Any {
        return when {
            isCustomAvatar(avatarRef) -> getCustomAvatarFile(avatarRef) ?: R.drawable.avatar_1
            isUrlAvatar(avatarRef) -> getUrlAvatarUrl(avatarRef)
            else -> getAvatarRes(avatarRef)
        }
    }
}