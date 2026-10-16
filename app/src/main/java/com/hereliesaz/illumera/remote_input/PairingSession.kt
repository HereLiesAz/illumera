package com.hereliesaz.illumera.remote_input

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Pure (Android-free) pairing-token state shared by every phone-as-input server.
 *
 * The token is only good while the session is live: it expires after
 * [idleTimeoutMs] without an authorized request or [absoluteTimeoutMs] after
 * creation, after [maxGlobalFailedAttempts] wrong pins in total, or once
 * [invalidate] is called (e.g. after a credential form was submitted). A peer
 * address that sends [maxFailedAttempts] wrong pins is locked out on its own, so
 * one misbehaving LAN device cannot end the session for the legitimate phone.
 */
class PairingSession(
    private val pairingToken: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val absoluteTimeoutMs: Long = ABSOLUTE_TIMEOUT_MS,
    private val maxFailedAttempts: Int = MAX_FAILED_ATTEMPTS,
    private val maxGlobalFailedAttempts: Int = MAX_GLOBAL_FAILED_ATTEMPTS
) {
    enum class Result { AUTHORIZED, WRONG_PIN, EXPIRED }

    private val createdAt = clock()
    private var lastActivity = createdAt
    private var failedAttempts = 0
    private val failedAttemptsByPeer = HashMap<String, Int>()
    private var invalidated = false

    /**
     * Checks a request's pin. A missing pin (e.g. a browser's /favicon.ico) is a
     * WRONG_PIN but isn't counted as a guess, since it reveals nothing. [peer] is
     * the requester's address; a locked-out peer gets EXPIRED even with the right pin.
     */
    @Synchronized
    fun check(pin: String?, peer: String? = null): Result {
        if (isExpiredLocked()) return Result.EXPIRED
        val peerKey = peer.orEmpty()
        if ((failedAttemptsByPeer[peerKey] ?: 0) >= maxFailedAttempts) return Result.EXPIRED
        if (pin == null || !constantTimeEquals(pin, pairingToken)) {
            if (pin == null) return Result.WRONG_PIN
            val peerFailures = (failedAttemptsByPeer[peerKey] ?: 0) + 1
            failedAttemptsByPeer[peerKey] = peerFailures
            if (++failedAttempts >= maxGlobalFailedAttempts) invalidated = true
            return if (invalidated || peerFailures >= maxFailedAttempts) Result.EXPIRED else Result.WRONG_PIN
        }
        lastActivity = clock()
        return Result.AUTHORIZED
    }

    /** Makes the token single-use from here on: every later request is EXPIRED. */
    @Synchronized
    fun invalidate() {
        invalidated = true
    }

    @Synchronized
    fun isExpired(): Boolean = isExpiredLocked()

    private fun isExpiredLocked(): Boolean {
        if (invalidated) return true
        val now = clock()
        if (now - createdAt >= absoluteTimeoutMs || now - lastActivity >= idleTimeoutMs) {
            invalidated = true
        }
        return invalidated
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        const val ABSOLUTE_TIMEOUT_MS = 30 * 60 * 1000L
        /** Wrong pins allowed per peer address before that peer is locked out. */
        const val MAX_FAILED_ATTEMPTS = 10
        /** Wrong pins allowed across all peers before the token is invalidated. */
        const val MAX_GLOBAL_FAILED_ATTEMPTS = 100

        /** Constant-time string comparison for pins and CSRF tokens. */
        fun constantTimeEquals(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
        }

        /**
         * True for loopback, private IPv4 (10/8, 172.16/12, 192.168/16), IPv4
         * link-local (169.254/16), IPv6 link-local (fe80::/10) and ULA (fc00::/7).
         * Only IP literals are accepted; anything else (or null) is rejected
         * without a DNS lookup.
         */
        fun isLanAddress(ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            val literal = ip.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            val looksLikeIp = literal.contains(':') ||
                literal.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            if (!looksLikeIp) return false
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            if (address.isLoopbackAddress) return true
            return when (address) {
                is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress
                is Inet6Address -> {
                    val first = address.address[0].toInt() and 0xFF
                    address.isLinkLocalAddress || (first and 0xFE) == 0xFC
                }
                else -> false
            }
        }
    }
}

/**
 * Base for the LAN phone-as-input servers. Binds only to [hostname] (the LAN
 * address shown in the QR), rejects non-LAN peers, enforces [PairingSession]
 * on everything except `/ping`, and adds no-store / no-referrer headers to
 * every response so the pin in the URL isn't cached or leaked via Referer.
 */
abstract class PairedLanServer(
    hostname: String,
    port: Int,
    pairingToken: String
) : NanoHTTPD(hostname, port) {

    protected val pairingSession = PairingSession(pairingToken)

    /** Handles a request that already passed the address and pin checks. */
    protected abstract fun serveAuthorized(session: IHTTPSession): Response

    /** Status/body used when the pin is wrong (kept per-server for compatibility). */
    protected open fun wrongPinResponse(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    final override fun serve(session: IHTTPSession): Response = secure(route(session))

    private fun route(session: IHTTPSession): Response {
        // NanoHTTPD 2.3.1 records the peer IP in the "remote-addr" header.
        if (!PairingSession.isLanAddress(session.headers["remote-addr"])) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        }
        if (session.uri == "/ping") return DisconnectBanner.pingResponse()
        return when (pairingSession.check(session.parms["pin"], session.headers["remote-addr"])) {
            PairingSession.Result.AUTHORIZED -> serveAuthorized(session)
            PairingSession.Result.WRONG_PIN -> wrongPinResponse()
            PairingSession.Result.EXPIRED -> expiredResponse()
        }
    }

    /**
     * Returns a 413/411 response when [session]'s declared Content-Length is missing,
     * malformed or above [maxBytes]; null when the body may be parsed. Call before
     * parseBody(), which would otherwise buffer an arbitrarily large body.
     */
    protected fun rejectOversizedBody(session: IHTTPSession, maxBytes: Long): Response? {
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, MIME_PLAINTEXT, "Length required")
        if (declared < 0 || declared > maxBytes) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT, "Request too large")
        }
        return null
    }

    private fun expiredResponse(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Session expired</title></head>
            <body style="font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding:40px 20px;">
            <h1 style="font-size:1.4rem;">Session expired</h1>
            <p style="color:#aaa;">Session expired — reopen the dialog on the TV.</p>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/html", html)
    }

    private fun secure(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
    }
}
