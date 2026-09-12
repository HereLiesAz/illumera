package com.hereliesaz.illumera.data.model.stremio

enum class AddonCatalogSource(val displayName: String) {
    OFFICIAL("Official"),
    COMMUNITY("Community"),
    COLLECTION("Collection")
}

data class SavedAddonCollection(
    val name: String,
    val url: String
)

data class AddonCollectionLoadResult(
    val collection: SavedAddonCollection,
    val addons: List<AddonCatalogItem>
)

data class AddonCatalogResponse(
    val addons: List<AddonCatalogItem> = emptyList()
)

data class AddonCatalogItem(
    val transportUrl: String = "",
    val transportName: String = "http",
    val manifest: Manifest = Manifest(),
    val flags: AddonCatalogFlags? = null
) {
    val configureUrl: String?
        get() = if (
            isModernManifestTransport &&
            (manifest.behaviorHints?.configurable == true ||
                manifest.behaviorHints?.configurationRequired == true)
        ) {
            transportUrl
                .substringBeforeLast("/manifest.json", transportUrl.trimEnd('/'))
                .trimEnd('/') + "/configure"
        } else {
            null
        }

    val isModernManifestTransport: Boolean
        get() = transportUrl.substringBefore('?').trimEnd('/').endsWith("manifest.json", ignoreCase = true)
}

data class AddonCatalogFlags(
    val official: Boolean? = null,
    val protected: Boolean? = null
)

/** Legacy Stremio repository JSON. Endpoints are resolved to modern manifests when possible. */
data class LegacyAddonRepository(
    val name: String? = null,
    val addons: List<LegacyAddonRepositoryItem>? = null,
    val endpoints: List<String>? = null
)

data class LegacyAddonRepositoryItem(
    val id: String? = null,
    val name: String? = null,
    val logo: String? = null,
    val endpoints: List<String>? = null
)
