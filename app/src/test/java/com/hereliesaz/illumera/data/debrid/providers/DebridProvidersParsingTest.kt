package com.hereliesaz.illumera.data.debrid.providers

import com.google.gson.JsonParser
import com.hereliesaz.illumera.data.debrid.DebridHttp
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridItemOrigin
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersParsingTest {

    private val http = mockk<DebridHttp>()

    private fun json(s: String) = DebridResult.Success(JsonParser.parseString(s))

    private fun <T> DebridResult<T>.value(): T {
        assertTrue("expected success, got $this", this is DebridResult.Success)
        return (this as DebridResult.Success).value
    }

    @Test
    fun realDebridToleratesNullFieldsAndTagsOrigin() = runTest {
        coEvery { http.get(match { it.endsWith("/torrents") }, any(), any()) } returns json(
            """[{"id":"T1","filename":null,"bytes":null,"status":"downloaded","progress":100,
                "hash":"ABC","links":["https://real-debrid.com/d/HOSTER",null]},
               {"filename":"no id"}]"""
        )
        coEvery { http.get(match { it.endsWith("/downloads") }, any(), any()) } returns json(
            """[{"id":"D1","filename":"movie.mkv","filesize":10,"download":"https://x.real-debrid.com/d/ABC/movie.mkv"}]"""
        )

        val items = RealDebridService(http).listLibrary("key").value()

        assertEquals(listOf("T1", "D1"), items.map { it.id })
        val torrent = items[0]
        assertEquals("Unknown", torrent.name)
        assertNull(torrent.sizeBytes)
        assertEquals(listOf("https://real-debrid.com/d/HOSTER"), torrent.directLinks)
        assertEquals(DebridItemOrigin.TORRENT, torrent.origin)
        assertEquals(DebridItemOrigin.DOWNLOAD, items[1].origin)
    }

    @Test
    fun torrinValidatesAndReadsTheDocumentedCompatibleLibrary() = runTest {
        coEvery { http.get("https://api.torrin.app/rest/1.0/user", any(), any()) } returns json(
            """{"username":"torrin-viewer","expiration":"2027-01-01T00:00:00Z"}"""
        )
        coEvery { http.get("https://api.torrin.app/rest/1.0/torrents", any(), any()) } returns json(
            """[{"id":"t42","filename":"Film.mkv","status":"downloaded","progress":100,"links":["https://torrin.app/d/42"]}]"""
        )

        val service = TorrinService(http)
        assertEquals("torrin-viewer", service.validateApiKey("key").value().username)
        val item = service.listLibrary("key").value().single()
        assertEquals(DebridProvider.TORRIN, item.provider)
        assertEquals("t42", item.id)
        coVerify(exactly = 0) { http.get(match { it.endsWith("/downloads") }, any(), any()) }
    }

    @Test
    fun torrinUnrestrictAndDeleteUseTorrinHostNotRealDebrid() = runTest {
        coEvery { http.post(any(), any(), any(), any()) } returns json(
            """{"download":"https://cdn.torrin.app/film.mkv","filename":"film.mkv"}"""
        )
        coEvery { http.delete(any(), any()) } returns json("{}")
        val service = TorrinService(http)
        val item = DebridItem(
            id = "t42", name = "Film.mkv", provider = DebridProvider.TORRIN,
            directLinks = listOf("https://torrin.app/d/42")
        )

        assertEquals("https://cdn.torrin.app/film.mkv", service.getStreamUrl("key", item).value())
        service.deleteItem("key", item)

        coVerify(exactly = 1) { http.post("https://api.torrin.app/rest/1.0/unrestrict/link", any(), any(), any()) }
        coVerify(exactly = 1) { http.delete("https://api.torrin.app/rest/1.0/torrents/delete/t42", any()) }
    }

    @Test
    fun realDebridUnrestrictsTorrentLinksEvenWhenDownloaded() = runTest {
        coEvery { http.post(match { it.endsWith("/unrestrict/link") }, any(), any(), any()) } returns json(
            """{"download":"https://cdn/video.mkv","filename":"video.mkv","type":null}"""
        )
        val torrent = DebridItem(
            id = "T1", name = "t", provider = DebridProvider.REAL_DEBRID, status = "downloaded",
            directLinks = listOf("https://real-debrid.com/d/HOSTER")
        )

        assertEquals("https://cdn/video.mkv", RealDebridService(http).getStreamUrl("key", torrent).value())
    }

    @Test
    fun realDebridDeletesFromTheItemsOwnCollection() = runTest {
        coEvery { http.delete(any(), any()) } returns json("{}")
        val service = RealDebridService(http)
        val base = DebridItem(id = "X", name = "x", provider = DebridProvider.REAL_DEBRID, status = "downloaded",
            directLinks = listOf("https://real-debrid.com/d/X"))

        service.deleteItem("key", base)
        service.deleteItem("key", base.copy(origin = DebridItemOrigin.DOWNLOAD))

        coVerify { http.delete("https://api.real-debrid.com/rest/1.0/torrents/delete/X", any()) }
        coVerify { http.delete("https://api.real-debrid.com/rest/1.0/downloads/delete/X", any()) }
    }

    @Test
    fun premiumizeParsesTransfersWithNullSource() = runTest {
        coEvery { http.get(any(), any(), any()) } returns json(
            """{"status":"success","transfers":[
                {"id":"p1","name":"a","src":null,"status":"running","progress":0.42},
                {"id":"p2","name":"b","src":"magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"}]}"""
        )

        val items = PremiumizeService(http).listLibrary("key").value()

        assertEquals(42, items[0].progress)
        assertEquals(emptyList<String>(), items[0].directLinks)
        assertNull(items[0].infoHash)
        assertEquals("0123456789abcdef0123456789abcdef01234567", items[1].infoHash)
    }

    @Test
    fun torBoxDoesNotReportPlanAsPremiumUntil() = runTest {
        coEvery { http.get(any(), any(), any()) } returns json(
            """{"success":true,"data":{"email":"me@example.com","plan":2,"premium_expires_at":null}}"""
        )

        val info = TorBoxService(http).validateApiKey("key").value()

        assertEquals("me@example.com", info.username)
        assertNull(info.premiumUntil)
    }

    @Test
    fun torBoxListsFilesPreferringVideos() = runTest {
        coEvery { http.get(any(), any(), any()) } returns json(
            """{"success":true,"data":[{"id":7,"name":"pack","hash":null,"size":null,"progress":1,
                "files":[{"id":1,"name":"readme.txt"},{"id":2,"name":"ep.mkv"}]}]}"""
        )

        val item = TorBoxService(http).listLibrary("key").value().single()

        assertEquals("7", item.id)
        assertEquals(listOf("2"), item.directLinks)
        assertEquals(100, item.progress)
        assertNull(item.infoHash)
    }

    @Test
    fun debridLinkUsesNoAccountTypeAsExpiry() = runTest {
        coEvery { http.get(any(), any(), any()) } returns json(
            """{"success":true,"value":{"pseudo":"me","accountType":1}}"""
        )

        val info = DebridLinkService(http).validateApiKey("key").value()

        assertEquals("me", info.username)
        assertNull(info.premiumUntil)
    }

    @Test
    fun allDebridAndOffcloudTolerateNullFields() = runTest {
        coEvery { http.get(match { it.contains("alldebrid") }, any(), any()) } returns json(
            """{"status":"success","data":{"magnets":[{"id":5,"filename":"f","hash":null,"size":null,
                "links":[{"link":null},{"link":"https://alldebrid/l"}]}]}}"""
        )
        coEvery { http.get(match { it.contains("offcloud") }, any(), any()) } returns json(
            """[{"requestId":"r1","fileName":"a b.mkv","server":null,"fileSize":null}]"""
        )

        val ad = AllDebridService(http).listLibrary("key").value().single()
        assertEquals(listOf("https://alldebrid/l"), ad.directLinks)

        val oc = OffcloudService(http).listLibrary("key").value().single()
        assertEquals("r1", oc.id)
        assertEquals(emptyList<String>(), oc.directLinks)
    }
}
