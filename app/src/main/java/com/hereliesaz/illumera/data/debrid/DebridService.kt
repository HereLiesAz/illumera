package com.hereliesaz.illumera.data.debrid

import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridResult

/**
 * Common surface every debrid provider implementation exposes. Implementations live
 * under data/debrid/providers/ and are dispatched by [DebridManager] based on
 * [com.hereliesaz.illumera.data.model.debrid.DebridProvider].
 */
interface DebridService {

    /** Confirms the API key works and returns basic account info. */
    suspend fun validateApiKey(apiKey: String): DebridResult<DebridAccountInfo>

    /** Lists the items currently stored in the account's cloud (torrents/downloads/magnets). */
    suspend fun listLibrary(apiKey: String): DebridResult<List<DebridItem>>

    /** Resolves a playable direct-download URL for a library item, unrestricting it if needed. */
    suspend fun getStreamUrl(apiKey: String, item: DebridItem): DebridResult<String>

    /** Removes an item from the account's cloud storage. */
    suspend fun deleteItem(apiKey: String, item: DebridItem): DebridResult<Unit>

    /**
     * Asks the service to fetch [magnet] into the account (instant when it is cached there).
     * Services without a known add-magnet call report [supportsAddMagnet] false and are skipped.
     */
    val supportsAddMagnet: Boolean get() = false

    suspend fun addMagnet(apiKey: String, magnet: String): DebridResult<Unit> =
        DebridResult.Failure("Adding magnets is not supported")

    /** Like [getStreamUrl], preferring the file named [preferredFileName] in a multi-file item. */
    suspend fun getStreamUrl(apiKey: String, item: DebridItem, preferredFileName: String?): DebridResult<String> =
        getStreamUrl(apiKey, item)
}
