package com.hereliesaz.illumera.data.model.stremio

import com.google.gson.JsonElement

// Tells us what the addon can do (stream, catalog, meta)
data class Manifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,

    // List<JsonElement> accepts both Cinemeta (Strings) and Torrentio (Objects)
    val resources: List<JsonElement>? = null,

    val types: List<String>? = null,
    val catalogs: List<CatalogManifest>? = null,
    val addonCatalogs: List<CatalogManifest>? = null,
    val logo: String? = null,
    val background: String? = null,
    val idPrefixes: List<String>? = null,
    val behaviorHints: ManifestBehaviorHints? = null,
    val config: List<ManifestConfig>? = null,
    val contactEmail: String? = null
)

data class ManifestBehaviorHints(
    val adult: Boolean? = null,
    val p2p: Boolean? = null,
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null
)

data class ManifestConfig(
    val key: String = "",
    val type: String = "text",
    val default: String? = null,
    val title: String? = null,
    val description: String? = null,
    val options: List<String>? = null,
    val required: Boolean = false
)

data class CatalogManifest(
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val extra: List<CatalogExtra>? = null,
    val genres: List<String>? = null
)

data class CatalogExtra(
    val name: String = "",
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val optionsLimit: Int? = null
)
