package com.hereliesaz.illumera.data.model.stremio

enum class AddonCatalogSource(val displayName: String) {
    OFFICIAL("Official"),
    COMMUNITY("Community")
}

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
        get() = if (manifest.behaviorHints?.configurable == true ||
            manifest.behaviorHints?.configurationRequired == true
        ) {
            transportUrl
                .substringBeforeLast("/manifest.json", transportUrl.trimEnd('/'))
                .trimEnd('/') + "/configure"
        } else {
            null
        }
}

data class AddonCatalogFlags(
    val official: Boolean? = null,
    val protected: Boolean? = null
)
