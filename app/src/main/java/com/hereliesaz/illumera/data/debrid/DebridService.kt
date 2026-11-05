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

    /**
     * Like [getStreamUrl] for a pack: returns the [wanted] file, and a failure with
     * [NO_WANTED_FILE] when there are several videos and none is it. The default suits services
     * whose item already lists file names ([DebridItem.linkNames], narrowed by the manager).
     */
    /**
     * Fetches a direct (hoster) link through the service, so the viewer never connects to its
     * host. Only services with a known unrestrict call report [supportsUnrestrict] true.
     */
    val supportsUnrestrict: Boolean get() = false

    suspend fun unrestrictLink(apiKey: String, link: String): DebridResult<String> =
        DebridResult.Failure("Unrestricting links is not supported")

    suspend fun getStreamUrl(apiKey: String, item: DebridItem, wanted: WantedFile?): DebridResult<String> =
        getStreamUrl(apiKey, item)
}
