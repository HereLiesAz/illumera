package com.hereliesaz.illumera.data.debrid.providers

import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Torrin documents a RealDebrid-compatible /rest/1.0 API for user, torrents and
 * unrestriction. The optional RealDebrid /downloads collection is not documented.
 * https://docs.torrin.app/api-reference/introduction
 */
@Singleton
class TorrinService @Inject constructor(http: DebridHttp) : RealDebridService(http) {
    override val base = "https://api.torrin.app/rest/1.0"
    override val provider = DebridProvider.TORRIN
    override val hasDownloadsEndpoint = false
}
