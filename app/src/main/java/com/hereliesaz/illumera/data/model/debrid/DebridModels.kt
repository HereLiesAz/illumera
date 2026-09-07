package com.hereliesaz.illumera.data.model.debrid

/**
 * Every debrid service illumera knows how to talk to. Each has its own REST API
 * and auth scheme — see the matching class under data/debrid/providers/.
 */
enum class DebridProvider(val id: String, val displayName: String) {
    REAL_DEBRID("real_debrid", "Real-Debrid"),
    ALL_DEBRID("all_debrid", "AllDebrid"),
    PREMIUMIZE("premiumize", "Premiumize"),
    TORBOX("torbox", "TorBox"),
    DEBRID_LINK("debrid_link", "Debrid-Link"),
    OFFCLOUD("offcloud", "Offcloud"),
    EASY_DEBRID("easy_debrid", "EasyDebrid");

    companion object {
        fun fromId(id: String?): DebridProvider? = entries.firstOrNull { it.id == id }
    }
}

/** A single cloud-stored item (torrent, magnet, or direct download) in a debrid account's library. */
data class DebridItem(
    val id: String,
    val name: String,
    val provider: DebridProvider,
    val sizeBytes: Long? = null,
    val status: String? = null,
    val progress: Int? = null,
    val addedAt: String? = null,
    val infoHash: String? = null,
    /** Direct file links already known for this item, if the provider returns them up front. */
    val directLinks: List<String> = emptyList()
)

/** Minimal account info used to confirm a key is valid and show who's connected. */
data class DebridAccountInfo(
    val username: String,
    val premiumUntil: String? = null
)

sealed class DebridResult<out T> {
    data class Success<T>(val value: T) : DebridResult<T>()
    data class Failure(val message: String) : DebridResult<Nothing>()
}
