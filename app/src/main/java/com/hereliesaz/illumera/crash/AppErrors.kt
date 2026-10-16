package com.hereliesaz.illumera.crash

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.acra.ACRA
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Logs handled errors and reports them as non-fatal issues (GitHub build only, subject
 * to the same Settings opt-out as crash reports).
 *
 * Each report's stack trace is the call site, with the caught exception as its cause, so
 * the relay files one issue per place-that-failed. Within one app session a given call
 * site + error type is sent once, and at most [MAX_REPORTS_PER_SESSION] are sent in
 * total, so a retry loop cannot flood the tracker.
 */
object AppErrors {
    private const val MAX_REPORTS_PER_SESSION = 25
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val sent = AtomicInteger(0)

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        report(tag, message, error)
    }

    fun w(tag: String, message: String, error: Throwable) {
        Log.w(tag, message, error)
        report(tag, message, error)
    }

    private fun report(tag: String, message: String, error: Throwable?) {
        if (error is CancellationException) return
        if (isOffline(error)) return
        if (!CrashReporting.isAvailable() || !ACRA.isInitialised) return

        val callSite = Throwable().stackTrace
            .dropWhile { it.className == AppErrors::class.java.name }
        val key = "${callSite.firstOrNull()}|${error?.javaClass?.name}"
        if (!reported.add(key)) return
        if (sent.incrementAndGet() > MAX_REPORTS_PER_SESSION) return

        val handled = HandledError("[$tag] ${redact(message)}", error?.let(::redacted))
            .apply { stackTrace = callSite.toTypedArray() }
        runCatching { ACRA.errorReporter.handleSilentException(handled) }
    }
}

/**
 * The device couldn't reach the network (no DNS, no route, connection or call timed out).
 * Logged, never filed: it says nothing about the app, and one outage filed a dozen issues.
 */
internal fun isOffline(error: Throwable?): Boolean =
    generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
        .take(10)
        .any {
            it is java.net.UnknownHostException ||
                it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException ||
                it is java.net.SocketTimeoutException ||
                // OkHttp's call timeout ("timeout") and dropped connections.
                it is java.io.InterruptedIOException
        }

private val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^@/\s?#]*@)?([^/\s?#@]+)[^\s"')]*""")
private val MAGNET = Regex("""magnet:\?\S+""")

/**
 * Issues are public and stream URLs can carry account tokens (debrid links, addon
 * configs), so URLs keep only scheme and host, and magnet links are dropped entirely.
 */
internal fun redact(text: String?): String =
    text.orEmpty().replace(MAGNET, "magnet:<redacted>").replace(URL) { "${it.groupValues[1]}${it.groupValues[2]}/<redacted>" }

/** Copy of [error]'s cause chain with messages redacted and stacks kept. */
internal fun redacted(error: Throwable): Throwable =
    RedactedError(error.javaClass.name, redact(error.message), error.cause?.let(::redacted))
        .apply { stackTrace = error.stackTrace }

private class RedactedError(val originalClass: String, message: String, cause: Throwable?) :
    Exception(message, cause) {
    override fun toString(): String = "$originalClass: $message"
}

/** A caught error, reported without crashing. The cause is the original exception. */
class HandledError(message: String, cause: Throwable?) : Exception(message, cause)

/**
 * Wraps the current default uncaught-exception handler (ACRA's, once initialised) so it only
 * ever sees [redacted] copies: crash reports become public issues, and a fatal exception
 * message can embed a tokenised stream URL just like a handled one.
 */
internal fun installRedactingExceptionHandler() {
    val delegate = Thread.getDefaultUncaughtExceptionHandler() ?: return
    Thread.setDefaultUncaughtExceptionHandler(redactingHandler(delegate))
}

internal fun redactingHandler(delegate: Thread.UncaughtExceptionHandler) =
    Thread.UncaughtExceptionHandler { thread, error -> delegate.uncaughtException(thread, redacted(error)) }
