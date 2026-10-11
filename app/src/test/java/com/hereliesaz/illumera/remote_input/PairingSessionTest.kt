package com.hereliesaz.illumera.remote_input

import com.hereliesaz.illumera.remote_input.PairingSession.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSessionTest {

    private var now = 1_000_000L
    private val pin = "pin-123"
    private fun session() = PairingSession(pin, clock = { now })

    @Test
    fun correctPinIsAuthorized() {
        assertEquals(Result.AUTHORIZED, session().check(pin))
    }

    @Test
    fun wrongOrMissingPinIsRejected() {
        val s = session()
        assertEquals(Result.WRONG_PIN, s.check("nope"))
        assertEquals(Result.WRONG_PIN, s.check(null))
        assertEquals(Result.AUTHORIZED, s.check(pin))
    }

    @Test
    fun expiresAfterIdleTimeout() {
        val s = session()
        now += PairingSession.IDLE_TIMEOUT_MS - 1
        assertEquals(Result.AUTHORIZED, s.check(pin)) // refreshes activity
        now += PairingSession.IDLE_TIMEOUT_MS - 1
        assertEquals(Result.AUTHORIZED, s.check(pin))
        now += PairingSession.IDLE_TIMEOUT_MS
        assertEquals(Result.EXPIRED, s.check(pin))
        assertTrue(s.isExpired())
    }

    @Test
    fun expiresAfterAbsoluteTimeoutEvenWhenActive() {
        val s = session()
        repeat(4) {
            now += 8 * 60 * 1000L
            if (it < 3) assertEquals(Result.AUTHORIZED, s.check(pin))
        }
        assertEquals(Result.EXPIRED, s.check(pin))
    }

    @Test
    fun tooManyWrongPinsInvalidates() {
        val s = session()
        repeat(PairingSession.MAX_FAILED_ATTEMPTS - 1) { assertEquals(Result.WRONG_PIN, s.check("bad$it")) }
        assertEquals(Result.EXPIRED, s.check("bad-last"))
        assertEquals(Result.EXPIRED, s.check(pin))
    }

    @Test
    fun missingPinDoesNotCountAsGuess() {
        val s = session()
        repeat(PairingSession.MAX_FAILED_ATTEMPTS * 2) { s.check(null) }
        assertEquals(Result.AUTHORIZED, s.check(pin))
    }

    @Test
    fun invalidateMakesTokenSingleUse() {
        val s = session()
        assertEquals(Result.AUTHORIZED, s.check(pin))
        s.invalidate()
        assertEquals(Result.EXPIRED, s.check(pin))
    }

    @Test
    fun constantTimeEquals() {
        assertTrue(PairingSession.constantTimeEquals("abc", "abc"))
        assertFalse(PairingSession.constantTimeEquals("abc", "abd"))
        assertFalse(PairingSession.constantTimeEquals("abc", "abcd"))
        assertFalse(PairingSession.constantTimeEquals(null, "abc"))
    }

    @Test
    fun lanAddressesAccepted() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.20",
            "169.254.3.4", "::1", "fe80::1", "fe80::1%wlan0", "fd12:3456::1", "fc00::1"
        ).forEach { assertTrue(it, PairingSession.isLanAddress(it)) }
    }

    @Test
    fun publicOrInvalidAddressesRejected() {
        listOf(
            null, "", "8.8.8.8", "172.32.0.1", "100.64.0.1", "2001:4860::8888",
            "example.com", "localhost"
        ).forEach { assertFalse(it.toString(), PairingSession.isLanAddress(it)) }
    }
}
