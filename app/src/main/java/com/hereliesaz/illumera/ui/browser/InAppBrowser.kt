package com.hereliesaz.illumera.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.illumera.ui.addons.VoidButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A full-screen browser for pages the app hands off: addon configuration pages and the
 * sign-in and API-key pages of the integrations.
 *
 * A TV remote has no touch, so the D-pad drives a pointer: the arrows move it, OK clicks, and
 * pushing past the top or bottom edge scrolls the page (or, at the very top, reaches the
 * toolbar). Clicking a text field brings up the system keyboard; Back closes it, then goes back
 * a page, then closes the browser.
 *
 * Addon install links (`stremio://...`, or a manifest address) are not loaded: they go to
 * [onAddonLink] and the browser closes, so a configuration page can finish by installing.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun InAppBrowser(
    url: String,
    onClose: () -> Unit,
    onAddonLink: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnClose by rememberUpdatedState(onClose)
    val latestOnAddonLink by rememberUpdatedState(onAddonLink)

    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // Typing: the page's own view has focus so the keyboard can talk to it.
    var typing by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(Offset(200f, 200f)) }
    var surface by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val surfaceFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { surfaceFocus.requestFocus() } }
    DisposableEffect(Unit) {
        onDispose {
            web?.apply { stopLoading(); destroy() }
            web = null
        }
    }

    fun stopTyping() {
        typing = false
        web?.let {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0)
            it.isFocusableInTouchMode = false
            it.clearFocus()
        }
        runCatching { surfaceFocus.requestFocus() }
    }

    BackHandler {
        val page = web
        when {
            typing -> stopTyping()
            page != null && page.canGoBack() -> page.goBack()
            else -> latestOnClose()
        }
    }

    fun click() {
        val page = web ?: return
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, now, action, cursor.x, cursor.y, 0)
            page.dispatchTouchEvent(event)
            event.recycle()
        }
        // If that landed in a text field, give the page focus so the keyboard types into it.
        scope.launch {
            delay(150)
            page.evaluateJavascript(
                "(function(){var a=document.activeElement;return !!a&&(a.isContentEditable||" +
                    "/^(INPUT|TEXTAREA|SELECT)\$/.test(a.tagName))})()"
            ) { editable ->
                if (editable == "true") {
                    typing = true
                    page.isFocusableInTouchMode = true
                    page.requestFocus()
                    context.getSystemService(InputMethodManager::class.java)?.showSoftInput(page, 0)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            VoidButton("Back", { web?.goBack() }, Modifier.width(96.dp), enabled = canGoBack, compact = true)
            VoidButton("Forward", { web?.goForward() }, Modifier.width(112.dp), enabled = canGoForward, compact = true)
            VoidButton("Reload", { web?.reload() }, Modifier.width(104.dp), compact = true)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    title.ifBlank { displayHost(pageUrl) },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (title.isNotBlank()) {
                    Text(
                        displayHost(pageUrl),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            VoidButton("Close", { latestOnClose() }, Modifier.width(96.dp), compact = true)
        }
        Box(
            Modifier.fillMaxWidth().height(3.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
        ) {
            if (progress in 0.01f..0.99f) {
                Box(
                    Modifier.fillMaxWidth(progress).height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { surface = it }
                .focusRequester(surfaceFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (typing) return@onPreviewKeyEvent false
                    val page = web
                    val key = event.key
                    val isClick = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
                    if (isClick) {
                        if (event.type == KeyEventType.KeyUp) click()
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = cursorStep(event.nativeKeyEvent.repeatCount)
                    val maxX = (surface.width - 1).coerceAtLeast(0).toFloat()
                    val maxY = (surface.height - 1).coerceAtLeast(0).toFloat()
                    when (key) {
                        Key.DirectionLeft -> { cursor = cursor.copy(x = (cursor.x - step).coerceAtLeast(0f)); true }
                        Key.DirectionRight -> { cursor = cursor.copy(x = (cursor.x + step).coerceAtMost(maxX)); true }
                        Key.DirectionUp -> when {
                            cursor.y > 0f -> { cursor = cursor.copy(y = (cursor.y - step).coerceAtLeast(0f)); true }
                            page != null && page.canScrollVertically(-1) -> { page.scrollBy(0, -(step * 3).toInt()); true }
                            // Already at the top of the page: on to the toolbar.
                            else -> false
                        }
                        Key.DirectionDown -> when {
                            cursor.y < maxY -> { cursor = cursor.copy(y = (cursor.y + step).coerceAtMost(maxY)); true }
                            else -> { page?.scrollBy(0, (step * 3).toInt()); true }
                        }
                        else -> false
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // The pointer drives the page; the view itself only takes focus to type.
                        isFocusable = false
                        isFocusableInTouchMode = false
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                            override fun onReceivedTitle(view: WebView, received: String?) {
                                title = received.orEmpty()
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                when (val action = classifyLink(request.url.toString())) {
                                    LinkAction.Load -> false
                                    LinkAction.Block -> true
                                    is LinkAction.InstallAddon -> {
                                        val handler = latestOnAddonLink
                                        if (handler != null) {
                                            handler(action.manifestUrl)
                                            latestOnClose()
                                        } else {
                                            Toast.makeText(ctx, "Addon links can't be installed from here.", Toast.LENGTH_LONG).show()
                                        }
                                        true
                                    }
                                }

                            override fun onPageStarted(view: WebView, address: String, favicon: Bitmap?) {
                                pageUrl = address
                                title = ""
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                            }

                            override fun onPageFinished(view: WebView, address: String) {
                                pageUrl = address
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                            }
                        }
                        if (canOpenInBrowser(url)) loadUrl(url)
                        web = this
                    }
                }
            )
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = 13.dp.toPx(), center = cursor)
                drawCircle(Color.White, radius = 10.dp.toPx(), center = cursor)
                drawCircle(Color.Black, radius = 4.dp.toPx(), center = cursor)
            }
        }
        Text(
            if (typing) "Typing · Back closes the keyboard"
            else "Arrows move the pointer · OK clicks · Up past the top reaches the toolbar",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}
