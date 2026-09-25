package com.hereliesaz.illumera.data.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextGuardTest {

    /** Sends [url] through the guard to a stub that records the request instead of connecting. */
    private fun sentUrl(url: String): String {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .guardCleartext()
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return sent!!.url.toString()
    }

    @Test
    fun `public http is upgraded to https`() {
        assertEquals("https://addon.example.com/token123/manifest.json", sentUrl("http://addon.example.com/token123/manifest.json"))
        assertEquals("https://cdn.example.com:8443/x", sentUrl("http://cdn.example.com:8443/x"))
    }

    @Test
    fun `local hosts and https are left alone`() {
        assertEquals("http://127.0.0.1:8090/stream", sentUrl("http://127.0.0.1:8090/stream"))
        assertEquals("http://192.168.1.20:7000/manifest.json", sentUrl("http://192.168.1.20:7000/manifest.json"))
        assertEquals("https://example.com/a", sentUrl("https://example.com/a"))
    }

    @Test
    fun `local host detection`() {
        listOf("localhost", "10.0.0.5", "172.16.0.1", "172.31.255.1", "192.168.0.2", "169.254.1.1", "nas.local", "::1", "fd00::1")
            .forEach { assertTrue(it, CleartextGuard.isLocalHost(it)) }
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "example.com", "2001:db8::1", "10.example.com")
            .forEach { assertFalse(it, CleartextGuard.isLocalHost(it)) }
    }
}

