package com.hereliesaz.illumera.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.navigation3.runtime.NavEntryDecorator
import com.hereliesaz.illumera.data.model.ProfileEntity

/** What opened the menu, which decides what Back does while it is open. */
enum class MenuOpener {
    /** Back at a main screen with nothing left to unwind ([MainRootBackHandler]). */
    RootBack,

    /** D-pad Left off the content's edge (or Up into the top bar), or a screen's own opener. */
    Left,

    /** A rightward touch swipe from the left screen edge. */
    Swipe
}

/**
 * What [MainShell] shares with the back-stack entries under it: whether the menu is open
 * (holds focus), what opened it, and the Back policy while it is open.
 *
 * Back with the menu open: when root Back opened it, a second Back leaves the app; when Left
 * or a swipe opened it, Back closes it and focus goes back where it was. Right, a tap beside
 * the menu and a rightward swipe always close it.
 */
@Stable
class MainShellState {
    internal val contentState = NavDrawerContentState()

    /** True while the menu (side rail or top bar) holds focus. */
    var isMenuOpen by mutableStateOf(false)
        internal set

    /** What opened the menu that is open now; null while it is closed. */
    var openedBy by mutableStateOf<MenuOpener?>(null)
        internal set

    /** Set just before an open that is not a Left move, and read as the menu takes focus. */
    internal var pendingOpener: MenuOpener? = null

    internal var closeMenuAction: () -> Unit = {}
    internal var exitAction: () -> Unit = {}

    /** Closes the menu: focus goes back to the content, restored where the layout allows. */
    fun closeMenu() = closeMenuAction()

    /** Back while the menu is open: exit if root Back opened it, otherwise close it. */
    fun onMenuBack() {
        if (openedBy == MenuOpener.RootBack) exitAction() else closeMenu()
    }

    /**
     * Root Back: opens the menu at [requester] (the current screen's item), or at [fallback]
     * when that item is hidden. Goes through [openNavDrawer], the only sanctioned opener.
     */
    fun openMenuFromRootBack(requester: FocusRequester?, fallback: FocusRequester? = null): Boolean =
        openMenu(MenuOpener.RootBack, requester, fallback)

    internal fun openMenu(opener: MenuOpener, requester: FocusRequester?, fallback: FocusRequester? = null): Boolean {
        pendingOpener = opener
        val opened = runCatching { requester?.openNavDrawer() == true }.getOrDefault(false) ||
            runCatching { fallback?.openNavDrawer() == true }.getOrDefault(false)
        if (!opened) pendingOpener = null
        return opened
    }

    internal fun onChromeFocusChanged(hasFocus: Boolean) {
        if (hasFocus && !isMenuOpen) {
            openedBy = pendingOpener ?: MenuOpener.Left
            pendingOpener = null
        } else if (!hasFocus) {
            openedBy = null
        }
        isMenuOpen = hasFocus
    }
}

/** The [MainShellState] over the current back-stack entry; null outside [MainShell]. */
val LocalMainShellState = staticCompositionLocalOf<MainShellState?> { null }

@Composable
fun rememberMainShellState(): MainShellState = remember { MainShellState() }

/**
 * Registers the open menu's Back inside each back-stack entry, after the entry's own content:
 * [MainShellState.onMenuBack], which exits the app when root Back opened the menu and closes
 * it when Left or a swipe did. Back handlers run last-registered first, and an entry's screen
 * registers its own (a main screen's [MainRootBackHandler], for one) whenever it is composed
 * again, e.g. on the way back from a Details page; registering this after them keeps the open
 * menu first.
 */
@Composable
fun <T : Any> rememberMenuBackNavEntryDecorator(state: MainShellState): NavEntryDecorator<T> =
    remember(state) {
        NavEntryDecorator { entry ->
            entry.Content()
            BackHandler(enabled = state.isMenuOpen) { state.onMenuBack() }
        }
    }

/**
 * A main screen's last Back: opens the menu (through [openNavDrawer]) once the screen has
 * nothing of its own left to unwind. Register it once per main-root entry, before the screen's
 * content, so the screen's own handlers (a search's keyboard and query, Settings' panels) are
 * registered later and run first. With the menu open, the menu's Back takes over.
 *
 * [menuRequester] is the screen's menu item; [fallbackRequester] (Home's) is used when the
 * profile hides that item.
 */
@Composable
fun MainRootBackHandler(
    state: MainShellState,
    menuRequester: FocusRequester,
    fallbackRequester: FocusRequester? = null
) {
    BackHandler(enabled = !state.isMenuOpen) {
        state.openMenuFromRootBack(menuRequester, fallbackRequester)
    }
}

/**
 * The app's screens with the menu chrome over them.
 *
 * [content] (the back stack's NavDisplay) sits at one fixed place in the tree, inside the side
 * menu's content layer, whatever the chrome does. The chrome, the side rail or the top bar
 * as [navPosition] says, is drawn over it only while [showChrome] (a main screen or the grid
 * is on top). Switching the menu layout crossfades the chrome alone, so the back stack, each
 * screen's state and the focused element all stay as they were.
 *
 * The NavDisplay should carry [rememberMenuBackNavEntryDecorator] with the same [state].
 * [onExit] leaves the app: the menu's Exit item, and Back on a menu that root Back opened.
 */
@Composable
fun MainShell(
    navPosition: String,
    showChrome: Boolean,
    currentDestination: NavDestination,
    currentProfile: ProfileEntity?,
    menuRequesters: Map<NavDestination, FocusRequester>,
    onNavigate: (NavDestination) -> Unit,
    onEnterContent: () -> Unit,
    onLogout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    state: MainShellState = rememberMainShellState(),
    content: @Composable () -> Unit
) {
    val contentState = state.contentState
    val isTop = navPosition == "top"
    SideEffect {
        // The same close as the chrome's own: the side menu restores the content's focused
        // element first; the top bar hands focus to the screen's entry point.
        state.closeMenuAction = if (isTop) {
            onEnterContent
        } else {
            { contentState.returnFocusToContent(onEnterContent) }
        }
        state.exitAction = onExit
    }
    Box(
        // Navigation sounds are Compose's own focus-move sounds, switched per window by
        // FollowNavigationSounds. An extra click played here doubled every move and ignored
        // the setting in dialogs, so there is none.
        modifier = modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize().navDrawerContentLayer(contentState)) {
            CompositionLocalProvider(LocalMainShellState provides state) {
                content()
            }
        }
        if (showChrome) {
            DisposableEffect(Unit) { onDispose { state.onChromeFocusChanged(false) } }
            Crossfade(
                targetState = navPosition,
                animationSpec = tween(400),
                label = "NavSwitcher",
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { state.onChromeFocusChanged(it.hasFocus) }
            ) { position ->
                if (position == "top") {
                    TopNavigationBarOverlay(
                        currentDestination = currentDestination,
                        currentProfile = currentProfile,
                        topNavRequesters = menuRequesters,
                        onNavigate = { destination ->
                            state.closeMenu()
                            onNavigate(destination)
                        },
                        onEnterContent = onEnterContent,
                        onLogout = onLogout,
                        onExit = onExit,
                        onBack = state::onMenuBack
                    )
                } else {
                    NavDrawerRail(
                        contentState = contentState,
                        currentDestination = currentDestination,
                        currentProfile = currentProfile,
                        drawerRequesters = menuRequesters,
                        onNavigate = { destination ->
                            state.closeMenu()
                            onNavigate(destination)
                        },
                        onClose = onEnterContent,
                        onBack = state::onMenuBack,
                        onSwipeOpen = { state.pendingOpener = MenuOpener.Swipe }
                    )
                }
            }
        }
    }
}
