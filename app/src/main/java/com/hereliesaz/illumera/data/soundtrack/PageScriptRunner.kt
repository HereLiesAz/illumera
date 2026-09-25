package com.hereliesaz.illumera.data.soundtrack

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Loads a page in a real browser engine and reads it with a script. */
interface PageScriptRunner {
    /**
     * Loads [url] and evaluates [script] until it returns a non-null string or [timeoutMs]
     * passes. The script sees the page as rendered, after any interstitial has cleared.
     */
    suspend fun run(url: String, script: String, timeoutMs: Long): String?
}

/**
 * [PageScriptRunner] backed by an off-screen WebView on the device. Cookies persist in the
 * shared CookieManager, so an interstitial passed once is not shown again. One page at a time.
 */
@Singleton
class WebViewPageScriptRunner @Inject constructor(
    @ApplicationContext private val context: Context,
) : PageScriptRunner {
    private val gson = Gson()
    private val lock = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun run(url: String, script: String, timeoutMs: Long): String? = lock.withLock {
        withContext(Dispatchers.Main) {
            // Some TV builds ship without a WebView provider; treat that as "no data".
            val webView = runCatching { WebView(context) }.getOrNull() ?: return@withContext null
            try {
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.loadUrl(url)
                withTimeoutOrNull(timeoutMs) {
                    var result: String? = null
                    while (result == null) {
                        delay(POLL_MS)
                        result = webView.evaluate(script)
                    }
                    result
                }
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    /** evaluateJavascript hands back JSON: a quoted string, or `null`. */
    private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { raw ->
            val value = runCatching { gson.fromJson(raw, String::class.java) }.getOrNull()
            if (cont.isActive) cont.resume(value)
        }
    }

    private companion object {
        const val POLL_MS = 750L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PageScriptRunnerModule {
    @Binds
    abstract fun bindPageScriptRunner(impl: WebViewPageScriptRunner): PageScriptRunner
}
