package com.hereliesaz.illumera.data.profile

import android.content.Context
import com.google.gson.Gson
import com.hereliesaz.illumera.data.model.stremio.Manifest

/**
 * Addons baked into the APK: Cinemeta (metadata baseline) and Streaming Catalogs
 * (Netflix, HBO Max, Disney+, … rows). Their manifests ship in
 * `assets/baked_addons/`, so a profile gets their catalogs even when the provider
 * is unreachable at install time, and Stremio account sync never prunes them.
 */
object BakedAddons {
    const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    const val STREAMING_CATALOGS_URL = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"

    val urls: Set<String> = setOf(CINEMETA_URL, STREAMING_CATALOGS_URL)

    private val assets = mapOf(
        CINEMETA_URL to "baked_addons/cinemeta.json",
        STREAMING_CATALOGS_URL to "baked_addons/streaming_catalogs.json"
    )

    fun isBaked(transportUrl: String): Boolean =
        transportUrl.removeSuffix("/manifest.json").trimEnd('/') in urls

    /** The bundled manifest for a baked addon, or null for any other URL. */
    fun manifest(context: Context, transportUrl: String): Manifest? {
        val path = assets[transportUrl.removeSuffix("/manifest.json").trimEnd('/')] ?: return null
        return runCatching {
            context.assets.open(path).bufferedReader().use { Gson().fromJson(it, Manifest::class.java) }
        }.getOrNull()
    }
}
