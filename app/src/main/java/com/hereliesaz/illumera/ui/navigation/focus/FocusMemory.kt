package com.hereliesaz.illumera.ui.navigation.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.node.ModifierNodeElement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import com.hereliesaz.illumera.ui.navigation.LocalMainShellState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A screen's place: the key of the element that last held focus, kept in the back-stack
 * entry's saved state, plus the elements currently on screen that can take it back.
 *
 * - [restorableFocus] tags an element with a key; focusing it records the key.
 * - [RestoreFocusOnResume] puts focus back on the recorded key when the entry resumes (first
 *   shown, or shown again by Back), and when a refresh removes the focused element.
 * - [focusMemoryRoot] marks the screen's outer bounds, so restore can tell whether anything in
 *   the screen still holds focus.
 *
 * Never Modifier.focusRestorer(): that also redirects a screen's own requestFocus() calls.
 * Only the screen's own elements are ever focused, never the menu.
 */
@Stable
class FocusMemory(initialKey: String? = null) {

    /** The last focused tagged element's key, saved with the entry. */
    var savedKey: String? by mutableStateOf(initialKey)
        private set

    /** Whether any element inside [focusMemoryRoot] has focus. */
    var screenHasFocus: Boolean by mutableStateOf(false)
        private set

    /** Counts focused tagged elements removed from the screen (a refresh re-keyed or dropped them). */
    internal var focusedRemovals by mutableIntStateOf(0)
        private set

    private var holdRestoreUntilMs = 0L

    /**
     * A deliberate focus jump (e.g. Up to the Hero) re-lays out the row it leaves in the same
     * frame, which reads as "the focused element was removed". Holding restore for a moment keeps
     * the viewer where the jump took them instead of pulling them back.
     */
    fun holdRestore(durationMs: Long = 700L) {
        holdRestoreUntilMs = android.os.SystemClock.uptimeMillis() + durationMs
    }

    internal val restoreHeld: Boolean
        get() = android.os.SystemClock.uptimeMillis() < holdRestoreUntilMs

    /** The removed focused element's key, to go back to. */
    internal var removedKey: String? = null
        private set

    private val nodes = mutableMapOf<String, RestorableFocusNode>()

    // The tagged element that lost focus in the current frame. Focus leaves an element that is
    // being removed just before the element goes (and the window hands focus to whatever it
    // finds first); an element that loses focus and then goes in the same frame was removed
    // while focused. One that loses focus to a move and scrolls away later is not.
    private var blurredThisFrame: RestorableFocusNode? = null
    private var blurredKey: String? = null

    /** Keys of the tagged elements composed right now. */
    val registeredKeys: Set<String> get() = nodes.keys

    fun record(key: String) {
        savedKey = key
    }

    fun forget() {
        savedKey = null
    }

    /** Focuses the tagged element [key], if it is composed. Returns whether focus moved there. */
    fun requestFocus(key: String): Boolean {
        val node = nodes[key] ?: return false
        return runCatching { node.requestFocus() }.getOrDefault(false)
    }

    /**
     * The composed tagged element nearest to [savedKey] when that one is gone. Keys of the form
     * "<group>:<index>" (a row's items) resolve to the same group's closest index; others have
     * no neighbour.
     */
    fun nearestRegistered(savedKey: String): String? {
        val (group, index) = splitIndexedKey(savedKey) ?: return null
        return nodes.keys
            .mapNotNull { key -> splitIndexedKey(key)?.takeIf { it.first == group }?.let { key to it.second } }
            .minWithOrNull(compareBy<Pair<String, Int>> { kotlin.math.abs(it.second - index) }.thenBy { it.second })
            ?.first
    }

    internal fun register(key: String, node: RestorableFocusNode) {
        nodes[key] = node
    }

    internal fun unregister(key: String, node: RestorableFocusNode) {
        if (nodes[key] === node) nodes.remove(key)
    }

    internal fun onBlurred(node: RestorableFocusNode, key: String) {
        blurredThisFrame = node
        blurredKey = key
    }

    internal fun onFrameAfterBlur(node: RestorableFocusNode) {
        if (blurredThisFrame === node) blurredThisFrame = null
    }

    internal fun onDetached(node: RestorableFocusNode, key: String, stillFocused: Boolean) {
        when {
            // Removed while it still had focus (focus is cleared after the element goes).
            stillFocused -> removedKey = key
            // Lost focus in this frame, just before it went.
            blurredThisFrame === node -> removedKey = blurredKey
            else -> return
        }
        blurredThisFrame = null
        focusedRemovals++
    }

    /** Restore takes the viewer back to the removed element (or, failing that, its neighbour). */
    internal fun rememberRemovedKey() {
        removedKey?.let { savedKey = it }
    }

    internal fun onScreenFocusChanged(hasFocus: Boolean) {
        screenHasFocus = hasFocus
    }

    companion object {
        private const val NO_KEY = "\u0000"

        val Saver: Saver<FocusMemory, String> = Saver(
            save = { it.savedKey ?: NO_KEY },
            restore = { FocusMemory(it.takeUnless { key -> key == NO_KEY }) }
        )

        private fun splitIndexedKey(key: String): Pair<String, Int>? {
            val separator = key.lastIndexOf(':')
            if (separator <= 0) return null
            val index = key.substring(separator + 1).toIntOrNull() ?: return null
            return key.substring(0, separator) to index
        }
    }
}

/**
 * The FocusMemory of the screen around a shared component (Home's rows), for the components
 * that tag their elements with [restorableFocus] only when a screen asks for it.
 */
val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** This back-stack entry's [FocusMemory]; its saved key survives the entry leaving the screen. */
@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }

/** Tags this element as [key] in [memory]: focusing it records the key, and restore can find it. */
fun Modifier.restorableFocus(key: String, memory: FocusMemory): Modifier =
    this then RestorableFocusElement(key, memory)

/** [restorableFocus] when the screen provides a memory ([LocalFocusMemory]); otherwise nothing. */
fun Modifier.restorableFocusIfTracked(key: String, memory: FocusMemory?): Modifier =
    if (memory == null) this else restorableFocus(key, memory)

/** The screen's outer bounds for [memory]: everything focusable in the screen sits inside. */
fun Modifier.focusMemoryRoot(memory: FocusMemory): Modifier =
    onFocusChanged { memory.onScreenFocusChanged(it.hasFocus) }

private data class RestorableFocusElement(
    val key: String,
    val memory: FocusMemory
) : ModifierNodeElement<RestorableFocusNode>() {
    override fun create() = RestorableFocusNode(key, memory)
    override fun update(node: RestorableFocusNode) = node.update(key, memory)
}

internal class RestorableFocusNode(
    private var key: String,
    private var memory: FocusMemory
) : Modifier.Node(), FocusRequesterModifierNode, FocusEventModifierNode {

    private var focused = false

    override fun onAttach() {
        memory.register(key, this)
    }

    override fun onDetach() {
        memory.unregister(key, this)
        memory.onDetached(this, key, stillFocused = focused)
        focused = false
    }

    fun update(key: String, memory: FocusMemory) {
        if (key == this.key && memory === this.memory) return
        if (isAttached) this.memory.unregister(this.key, this)
        this.key = key
        this.memory = memory
        if (isAttached) memory.register(key, this)
        if (focused) memory.record(key)
    }

    override fun onFocusEvent(focusState: FocusState) {
        val wasFocused = focused
        focused = focusState.hasFocus
        if (focused) {
            memory.record(key)
        } else if (wasFocused && isAttached) {
            memory.onBlurred(this, key)
            val blurredIn = memory
            coroutineScope.launch {
                withFrameNanos { }
                blurredIn.onFrameAfterBlur(this@RestorableFocusNode)
            }
        }
    }
}

/** How many frames restore waits for a target that is not composed yet (about half a second). */
private const val RESTORE_ATTEMPT_FRAMES = 30

/**
 * Puts focus back where the viewer left this screen, when its back-stack entry reaches RESUMED
 * (first shown, or shown again when Back pops a page above it) and once [enabled] (the screen's
 * content is there).
 *
 * The order: scroll to [FocusMemory.savedKey] with [scrollTo], wait a frame, focus it; if it is
 * gone, [neighbour] (by default the same row's nearest index); otherwise [fallback], the screen's
 * primary item. While the screen stays up, a focused tagged element that a refresh removes
 * (re-keyed or dropped) is restored the same way, unless the menu is open.
 */
@Composable
fun RestoreFocusOnResume(
    memory: FocusMemory,
    fallback: FocusRequester?,
    enabled: Boolean = true,
    scrollTo: suspend (String) -> Unit = {},
    neighbour: (String) -> String? = { memory.nearestRegistered(it) }
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shell = LocalMainShellState.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentScrollTo by rememberUpdatedState(scrollTo)
    val currentNeighbour by rememberUpdatedState(neighbour)
    val currentFallback by rememberUpdatedState(fallback)
    // Per composition: a page shown again (Back) is a new composition and restores again.
    val restored = remember { booleanArrayOf(false) }

    suspend fun restore() = restoreFocus(memory, currentFallback, currentScrollTo, currentNeighbour)

    // Shown (or shown again), resumed and with its content: back to the viewer's place.
    LaunchedEffect(memory, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {}
        if (!restored[0]) {
            restored[0] = true
            restore()
        } else if (!memory.screenHasFocus && shell?.isMenuOpen != true) {
            // The content came back (e.g. reloaded) while nothing in the screen held focus.
            restore()
        }
    }

    // A refresh removed the focused element, and the window put focus on whatever it found
    // first: take the viewer back to the element (re-keyed, it is composed again) or its
    // neighbour. Not while the menu is open or the page is going away.
    LaunchedEffect(memory) {
        var seen = memory.focusedRemovals
        snapshotFlow { memory.focusedRemovals }.collect { removals ->
            if (removals == seen) return@collect
            seen = removals
            withFrameNanos { }
            if (shell?.isMenuOpen == true) return@collect
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
            if (memory.restoreHeld) return@collect
            if (!restored[0] || !currentEnabled) return@collect
            memory.rememberRemovedKey()
            restore()
        }
    }
}

/** Saved key, then its neighbour, then the fallback. Returns whether something took focus. */
internal suspend fun restoreFocus(
    memory: FocusMemory,
    fallback: FocusRequester?,
    scrollTo: suspend (String) -> Unit,
    neighbour: (String) -> String?
): Boolean {
    val saved = memory.savedKey
    if (saved != null) {
        if (focusKey(memory, saved, scrollTo)) return true
        val near = neighbour(saved)
        if (near != null && near != saved && focusKey(memory, near, scrollTo)) return true
    }
    if (fallback == null) return false
    repeat(RESTORE_ATTEMPT_FRAMES) {
        if (tryRequest(fallback)) return true
        withFrameNanos { }
    }
    return false
}

private suspend fun focusKey(memory: FocusMemory, key: String, scrollTo: suspend (String) -> Unit): Boolean {
    try {
        scrollTo(key)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A key the screen can't scroll to still gets its focus attempt.
    }
    withFrameNanos { }
    repeat(3) {
        if (memory.requestFocus(key)) return true
        withFrameNanos { }
    }
    return false
}

private fun tryRequest(requester: FocusRequester): Boolean =
    runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
