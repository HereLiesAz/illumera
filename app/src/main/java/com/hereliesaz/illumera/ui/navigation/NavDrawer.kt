package com.hereliesaz.illumera.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.hereliesaz.illumera.R
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.ui.profiles.ProfileAssets
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.hereliesaz.illumera.ui.util.rememberIsTvDevice
import com.hereliesaz.illumera.ui.util.touchClick

enum class NavDestination(
    @DrawableRes val iconRes: Int,
    val label: String,
    val iconSize: Dp = 20.dp
) {
    Home(R.drawable.home_icon, "Home", iconSize = 21.dp),
    Movies(R.drawable.movies_icon, "Movies"),
    Series(R.drawable.series_icon, "Series"),
    Watchlist(R.drawable.watchlist_icon, "Watchlist"),
    Search(R.drawable.search_icon, "Search"),
    Profile(R.drawable.profile_icon, "Log Out", iconSize = 18.dp),
    Addons(R.drawable.puzzle_icon, "Add-ons"),
    Settings(R.drawable.settings_icon, "Settings"),
    Exit(R.drawable.exit_icon, "Exit", iconSize = 21.dp)
}

/**
 * The side menu around [content]: the content layer ([navDrawerContentLayer]) with the rail
 * ([NavDrawerRail]) over it. MainShell composes the two parts separately so the content keeps
 * its place in the tree when the chrome changes; this wrapper is the same pair in one piece.
 */
@Composable
fun NavDrawer(
    currentDestination: NavDestination,
    currentProfile: ProfileEntity?,
    drawerRequesters: Map<NavDestination, FocusRequester>,
    onNavigate: (NavDestination) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    val contentState = rememberNavDrawerContentState()
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().navDrawerContentLayer(contentState)) {
            content()
        }
        NavDrawerRail(
            contentState = contentState,
            currentDestination = currentDestination,
            currentProfile = currentProfile,
            drawerRequesters = drawerRequesters,
            onNavigate = onNavigate,
            onClose = onClose
        )
    }
}

/**
 * What the side menu knows about the content under it: whether the content holds focus
 * (the rail's entry guard only lets Left in from focused content), and the requester that
 * saves and restores the content's focused element around the menu opening and closing.
 */
@Stable
class NavDrawerContentState {
    val contentFocusRequester = FocusRequester()
    var contentHasFocus by mutableStateOf(false)
        internal set

    /** Puts focus back on the content element that had it when the menu opened. */
    @OptIn(ExperimentalComposeUiApi::class)
    fun restoreContentFocus(): Boolean =
        runCatching { contentFocusRequester.restoreFocusedChild() }.getOrDefault(false)

    /**
     * Leaves the menu for the content: the element that had focus, else [screenEntry] (the
     * screen's own entry point), else whatever the content layer offers first. The last step
     * matters when the saved element became unfocusable (a button disabled while it works)
     * and the entry point isn't composed (an empty list) — without it focus stayed in the
     * menu with no way out but another screen.
     */
    fun returnFocusToContent(screenEntry: () -> Unit) {
        if (restoreContentFocus()) return
        runCatching { screenEntry() }
        if (!contentHasFocus) runCatching { contentFocusRequester.requestFocus() }
    }
}

@Composable
fun rememberNavDrawerContentState(): NavDrawerContentState = remember { NavDrawerContentState() }

/**
 * The content layer under the side menu. It saves the focused content element as focus leaves
 * the content (i.e. as the menu opens) so closing the menu can restore it. Deliberately not
 * Modifier.focusRestorer(): that also redirects every programmatic requestFocus() on a child
 * back to the saved element, which would hijack screens focusing their own entry points.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.navDrawerContentLayer(state: NavDrawerContentState): Modifier = this
    .onFocusChanged { state.contentHasFocus = it.hasFocus }
    .focusProperties { onExit = { state.contentFocusRequester.saveFocusedChild() } }
    .focusRequester(state.contentFocusRequester)
    .focusGroup()

/**
 * The side menu's rail and its shading, drawn over content that carries
 * [navDrawerContentLayer] with the same [contentState].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NavDrawerRail(
    contentState: NavDrawerContentState,
    currentDestination: NavDestination,
    currentProfile: ProfileEntity?,
    drawerRequesters: Map<NavDestination, FocusRequester>,
    onNavigate: (NavDestination) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onSwipeOpen: () -> Unit = {}
) {
    var isMenuFocused by remember { mutableStateOf(false) }
    // Touch never produces a focus event, so a touch device gets no way to trigger
    // the D-pad "hover to expand" affordance below. Keeping the rail permanently
    // expanded instead would push its width from 80dp to 200dp, which every screen's
    // own content padding is sized against — so touch stays collapsed like TV at
    // rest (see the extraItemsAlpha override below for how Settings/Exit stay reachable).
    val isTv = rememberIsTvDevice()
    val isExpanded = isMenuFocused

    // OPEN / CLOSE CONTRACT
    // The menu is "open" exactly while it holds focus, and focus may only enter it
    // (see [navDrawerFocusGuard]) by moving LEFT: D-pad Left off the natural left edge of
    // the content, a screen's own Left-at-edge / Back-at-root handler calling
    // [openNavDrawer], or the swipe-from-left-edge gesture below. Default/initial focus,
    // Up/Down/Right traversal and any plain requestFocus() are refused.
    //
    // The content layer saves which of its children had focus whenever focus leaves it,
    // so closing the menu hands focus straight back to that element — and only falls
    // back to the screen's entry requester (onClose) when there is nothing to restore
    // (e.g. the menu was opened by touch, or the element is gone). Closing never selects
    // or navigates.
    val closeMenu: () -> Unit = { contentState.returnFocusToContent(onClose) }
    // Back while open. MainShell passes its policy (exit when root Back opened the menu);
    // on its own the rail closes, like Right.
    val backFromMenu: () -> Unit = onBack ?: closeMenu
    val openMenu: () -> Unit = {
        val opened = runCatching {
            drawerRequesters[currentDestination]?.openNavDrawer() == true
        }.getOrDefault(false)
        if (!opened) {
            // The current destination may not have a visible item (e.g. one
            // destination hidden by the profile's menu settings) — fall back to Home.
            runCatching { drawerRequesters[NavDestination.Home]?.openNavDrawer() }
        }
    }

    val width by animateDpAsState(
        targetValue = if (isExpanded) 200.dp else 80.dp,
        label = "NavWidth",
        animationSpec = tween(300)
    )

    // VISIBILITY ANIMATION: Profile/Settings/Exit are otherwise hidden until the rail
    // expands on D-pad focus — on touch there's no focus/hover, so keep them visible
    // (icon-only, since isMenuExpanded/label visibility below still follows isExpanded)
    // or they'd be permanently unreachable.
    val extraItemsAlpha by animateFloatAsState(
        targetValue = if (isExpanded || !isTv) 1f else 0f,
        label = "ExtraItemsAlpha",
        animationSpec = tween(300)
    )

    val showStaticMask = currentDestination in listOf(
        NavDestination.Home,
        NavDestination.Movies,
        NavDestination.Series,
        NavDestination.Watchlist,
        NavDestination.Addons
    )

    Box(modifier = modifier.fillMaxSize()) {

        // LAYER 1 (the content) is the caller's, under this overlay: see navDrawerContentLayer.

        // BackHandlers are dispatched last-composed first. Register this after screen content
        // so an open drawer wins over Watchlist (and any other screen-level BackHandler).
        // (MainShell also closes the menu from inside each back-stack entry: see
        // rememberMenuBackNavEntryDecorator.)
        BackHandler(enabled = isMenuFocused) {
            backFromMenu()
        }

        // LAYER 2: Static Hero Mask
        val backgroundColor = MaterialTheme.colorScheme.background
        // Brush.horizontalGradient's startX/endX are raw pixels, not dp — without this
        // conversion the gradient extents below were literal pixel counts, so they'd cut
        // off far short of their intended width on anything denser than mdpi (1x).
        val density = LocalDensity.current
        val maskGradientEndPx = with(density) { 350.dp.toPx() }
        val shadowGradientEndPx = with(density) { 900.dp.toPx() }
        if (showStaticMask) {
            Box(
                modifier = Modifier
                    .width(400.dp)
                    .fillMaxHeight()
                    .zIndex(1f)
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to backgroundColor.copy(alpha = 0.8f),
                                0.12f to backgroundColor.copy(alpha = 0.72f),
                                0.25f to backgroundColor.copy(alpha = 0.62f),
                                0.38f to backgroundColor.copy(alpha = 0.50f),
                                0.50f to backgroundColor.copy(alpha = 0.38f),
                                0.65f to backgroundColor.copy(alpha = 0.24f),
                                0.78f to backgroundColor.copy(alpha = 0.13f),
                                0.90f to backgroundColor.copy(alpha = 0.05f),
                                1.0f to Color.Transparent
                            ),
                            startX = 0f,
                            endX = maskGradientEndPx
                        )
                    )
            )
        }

        // LAYER 3: Dynamic Expansion Shadow
        // While the menu is open this layer covers everything to the right of the rail, so
        // any press or swipe there closes the menu (without selecting anything) instead of
        // reaching the content underneath. It stops intercepting the moment the menu closes,
        // so the 300ms fade-out never swallows a tap meant for content.
        androidx.compose.animation.AnimatedVisibility(
            visible = isMenuFocused,
            enter = androidx.compose.animation.fadeIn(animationSpec = tween(300)),
            exit = androidx.compose.animation.fadeOut(animationSpec = tween(300)),
            modifier = Modifier.zIndex(1.5f).fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isMenuFocused) {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false).consume()
                                    closeMenu()
                                }
                            }
                        } else Modifier
                    )
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to backgroundColor.copy(alpha = 0.95f),
                                0.12f to backgroundColor.copy(alpha = 0.90f),
                                0.25f to backgroundColor.copy(alpha = 0.82f),
                                0.38f to backgroundColor.copy(alpha = 0.70f),
                                0.50f to backgroundColor.copy(alpha = 0.55f),
                                0.65f to backgroundColor.copy(alpha = 0.38f),
                                0.78f to backgroundColor.copy(alpha = 0.20f),
                                0.90f to backgroundColor.copy(alpha = 0.08f),
                                1.0f to Color.Transparent
                            ),
                            startX = 0f,
                            endX = shadowGradientEndPx
                        )
                    )
            )
        }

        // Noise overlay to reduce gradient banding on budget panels
        com.hereliesaz.illumera.ui.components.NoiseOverlay(modifier = Modifier.zIndex(1.6f))

        // LAYER 4: Interactive Drawer
        val swipeThresholdPx = with(density) { 48.dp.toPx() }
        val edgeWidthPx = with(density) { NavDrawerEdgeSwipeWidth.toPx() }
        // Read inside the gesture callbacks, which outlive a single composition.
        val menuOpenState = rememberUpdatedState(isMenuFocused)
        val openMenuState = rememberUpdatedState(openMenu)
        val closeMenuState = rememberUpdatedState(closeMenu)
        val swipeOpenState = rememberUpdatedState(onSwipeOpen)
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .zIndex(2f)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> { closeMenu(); true }
                        Key.Back -> { backFromMenu(); true }
                        else -> false
                    }
                }
                // Touch: a rightward swipe that STARTS at the left screen edge opens the menu
                // (the collapsed rail sits on that edge, so the gesture lands here); a
                // rightward swipe on the open menu closes it. Taps still reach the items —
                // drag detection only claims the pointer once it has moved past touch slop.
                .pointerInput(swipeThresholdPx, edgeWidthPx) {
                    var horizontalDrag = 0f
                    var startedAtEdge = false
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            horizontalDrag = 0f
                            startedAtEdge = start.x <= edgeWidthPx
                        },
                        onDragCancel = { horizontalDrag = 0f },
                        onDragEnd = {
                            if (horizontalDrag >= swipeThresholdPx) {
                                if (menuOpenState.value) {
                                    closeMenuState.value()
                                } else if (startedAtEdge) {
                                    swipeOpenState.value()
                                    openMenuState.value()
                                }
                            }
                            horizontalDrag = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            horizontalDrag += dragAmount
                        }
                    )
                }
                .onFocusChanged { isMenuFocused = it.hasFocus }
                .padding(top = 30.dp, bottom = 30.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navDrawerFocusGuard(contentHasFocus = { contentState.contentHasFocus })
                    .focusGroup(),
                horizontalAlignment = Alignment.Start
            ) {

                // Helper to apply focus logic cleanly
                @Composable
                fun DrawerItem(dest: NavDestination, label: String? = null) {
                    val isSelected = currentDestination == dest

                    SidebarItem(
                        screen = dest,
                        customLabel = label,
                        isSelected = isSelected,
                        isMenuExpanded = isExpanded,
                        isDrawerActive = isExpanded,
                        onNavigate = onNavigate,
                        modifier = Modifier
                            .focusRequester(drawerRequesters[dest]!!)
                            // End of the rail: a Down focus search must never
                            // escape from Exit into the content behind the menu.
                            .focusProperties {
                                when (dest) {
                                    NavDestination.Exit -> {
                                        // A spatial focus search can otherwise skip the
                                        // adjacent Settings item in the bottom stack.
                                        up = drawerRequesters.getValue(NavDestination.Settings)
                                        down = FocusRequester.Cancel
                                    }
                                    NavDestination.Settings -> {
                                        down = drawerRequesters.getValue(NavDestination.Exit)
                                    }
                                    else -> Unit
                                }
                            }
                            .onPreviewKeyEvent {
                                menuItemKey(it, closeMenu, backFromMenu)
                            }
                    )
                }

                // 1. Profile Avatar (Custom component)
                Column(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(modifier = Modifier.graphicsLayer { alpha = extraItemsAlpha }) {
                        ProfileAvatarItem(
                            profile = currentProfile,
                            isMenuExpanded = isExpanded,
                            onNavigate = { onNavigate(NavDestination.Profile) },
                            modifier = Modifier
                                .focusRequester(drawerRequesters[NavDestination.Profile]!!)
                                // Upper boundary mirrors the Exit trap below.
                                .focusProperties { up = FocusRequester.Cancel }
                                .onPreviewKeyEvent {
                                    menuItemKey(it, closeMenu, backFromMenu)
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 2. Search
                DrawerItem(NavDestination.Search)

                Spacer(modifier = Modifier.weight(1f))

                // Middle Items. Queue is part of Watchlist now, so it is no longer a
                // separate visible destination.
                DrawerItem(NavDestination.Home)
                if (currentProfile?.menuMoviesEnabled != false) {
                    Spacer(modifier = Modifier.height(4.dp))
                    DrawerItem(NavDestination.Movies)
                }
                if (currentProfile?.menuSeriesEnabled != false) {
                    Spacer(modifier = Modifier.height(4.dp))
                    DrawerItem(NavDestination.Series)
                }
                if (currentProfile?.menuWatchlistEnabled != false) {
                    Spacer(modifier = Modifier.height(4.dp))
                    DrawerItem(NavDestination.Watchlist)
                }

                Spacer(modifier = Modifier.weight(1f))

                // Standalone add-on browser/manager lives alongside Settings.
                DrawerItem(NavDestination.Addons)

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Section: Settings & Exit
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Settings (Invisible when collapsed)
                    Box(modifier = Modifier.graphicsLayer { alpha = extraItemsAlpha }) {
                        DrawerItem(NavDestination.Settings)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Exit (Invisible when collapsed)
                    Box(modifier = Modifier.graphicsLayer { alpha = extraItemsAlpha }) {
                        DrawerItem(NavDestination.Exit)
                    }
                }
            }
        }
    }
}

/** A menu item's keys: Right closes the menu; Back follows the menu's Back policy. */
private fun menuItemKey(event: KeyEvent, close: () -> Unit, back: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionRight -> { close(); true }
        Key.Back -> { back(); true }
        else -> false
    }
}

/** Width of the left-edge strip a rightward touch swipe must start in to open the menu. */
internal val NavDrawerEdgeSwipeWidth = 24.dp

/**
 * The drawer's structural focus guard: focus may only ENTER the drawer by moving Left
 * from focused content (D-pad Left off the content's left edge), or through an explicit
 * [openNavDrawer]. Initial /
 * default focus (FocusDirection.Enter), a plain requestFocus(), and Up/Down/Right/Tab
 * traversal that happens to land on the rail are all refused. Once inside, directional
 * moves can't leak back out into arbitrary content either — Right/Back/tap/swipe close
 * the menu through NavDrawer's own close path, which restores the previous focus.
 */
internal fun Modifier.navDrawerFocusGuard(contentHasFocus: () -> Boolean): Modifier = focusProperties {
    onEnter = {
        val allowed = NavDrawerOpenGate.explicitOpen ||
            // D-pad Left traversal counts only when it starts FROM content: with nothing
            // focused (an item was removed, a dialog closed, a screen is still loading) a
            // Left press is an initial-focus search, not "Left off the content's edge".
            (requestedFocusDirection == FocusDirection.Left && contentHasFocus())
        if (!allowed) cancelFocusChange()
    }
    onExit = {
        when (requestedFocusDirection) {
            FocusDirection.Up, FocusDirection.Down, FocusDirection.Left,
            FocusDirection.Right, FocusDirection.Next, FocusDirection.Previous -> cancelFocusChange()
            else -> Unit
        }
    }
}

/**
 * Opens the side menu by focusing this drawer item. This is the ONLY sanctioned way for a
 * screen to move focus into the menu, and screens may only call it:
 *  - on D-pad Left when the focused element is genuinely at the content's left edge, or
 *  - on Back on a main screen once every other Back action it has is exhausted.
 * A plain requestFocus() on a drawer item is refused by [navDrawerFocusGuard].
 * In top-navigation mode the same requesters belong to the top bar, which has no guard,
 * so this also works there. Returns whether focus moved.
 */
fun FocusRequester.openNavDrawer(): Boolean {
    NavDrawerOpenGate.explicitOpen = true
    return try {
        requestFocus(FocusDirection.Left)
    } finally {
        NavDrawerOpenGate.explicitOpen = false
    }
}

/** Set only for the duration of an [openNavDrawer] call (focus transactions are synchronous). */
internal object NavDrawerOpenGate {
    var explicitOpen = false
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SidebarItem(
    screen: NavDestination,
    customLabel: String? = null,
    isSelected: Boolean,
    isMenuExpanded: Boolean,
    isDrawerActive: Boolean,
    onNavigate: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.primary

    val showIndicator = if (isDrawerActive) isFocused else isSelected

    val contentColor by animateColorAsState(
        targetValue = if (showIndicator) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        label = "contentColor"
    )

    val iconStartPadding = 20.dp

    val indicatorWidth by animateDpAsState(
        targetValue = if (showIndicator) 4.dp else 0.dp,
        label = "IndicatorWidth"
    )

    val textScale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        label = "TextScale"
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (isMenuExpanded) 1f else 0f,
        animationSpec = tween(300),
        label = "TextAlpha"
    )
    val textOffset by animateFloatAsState(
        targetValue = if (isMenuExpanded) 0f else -20f,
        animationSpec = tween(300),
        label = "TextOffset"
    )

    val displayText = customLabel ?: screen.label

    Box(
        modifier = modifier
            .height(50.dp)
            .fillMaxWidth()
    ) {
        Surface(
            onClick = { onNavigate(screen) },
            modifier = Modifier
                .fillMaxSize()
                .touchClick(onClick = { onNavigate(screen) })
                .onFocusChanged { isFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
                contentColor = contentColor,
                focusedContentColor = MaterialTheme.colorScheme.onBackground
            )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    painter = painterResource(id = screen.iconRes),
                    contentDescription = displayText,
                    tint = contentColor,
                    modifier = Modifier
                        .padding(start = iconStartPadding)
                        .size(screen.iconSize)
                )

                Text(
                    text = displayText,
                    fontSize = 13.sp,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .graphicsLayer {
                            scaleX = textScale
                            scaleY = textScale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            alpha = textAlpha
                            translationX = textOffset
                        }
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .height(22.dp)
                .width(indicatorWidth)
                .zIndex(10f)
                .background(
                    color = accentColor,
                    shape = RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)
                )
        )
    }
}

@Composable
fun ProfileAvatarItem(
    profile: ProfileEntity?,
    isMenuExpanded: Boolean,
    isDrawerActive: Boolean = true,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    
    val showIndicator = if (isDrawerActive) isFocused else false
    
    val contentColor by animateColorAsState(
        targetValue = if (showIndicator) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        label = "contentColor"
    )
    
    val borderColor by animateColorAsState(
        targetValue = if (showIndicator) accentColor else Color.Transparent,
        animationSpec = tween(200),
        label = "borderColor"
    )
    
    val avatarScale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = tween(200),
        label = "avatarScale"
    )
    
    val textScale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        label = "TextScale"
    )
    
    // Text only appears when profile item is focused
    val textAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(200),
        label = "TextAlpha"
    )
    
    val textOffset by animateFloatAsState(
        targetValue = if (isFocused) 0f else -20f,
        animationSpec = tween(200),
        label = "TextOffset"
    )
    
    val avatarSource = profile?.let { ProfileAssets.getAvatarSource(it.avatarRef) }
    val displayName = profile?.name ?: "Profile"
    
    Box(
        modifier = modifier
            .height(50.dp)
            .fillMaxWidth()
    ) {
        Surface(
            onClick = onNavigate,
            modifier = Modifier
                .fillMaxSize()
                .touchClick(onClick = onNavigate)
                .semantics { contentDescription = "Profile" }
                .onFocusChanged { isFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
                contentColor = contentColor,
                focusedContentColor = MaterialTheme.colorScheme.onBackground
            )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                // Avatar circle with border on focus
                Box(
                    modifier = Modifier
                        .padding(start = 15.dp)
                        .size(30.dp)
                        .graphicsLayer {
                            scaleX = avatarScale
                            scaleY = avatarScale
                        }
                        .clip(CircleShape)
                        .border(2.dp, borderColor, CircleShape)
                        .background(Color(0xFF1A1A1A))
                ) {
                    if (avatarSource != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(avatarSource)
                                .size(100, 100)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Profile Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                
                // Profile name and Change Profile text
                Column(
                    verticalArrangement = Arrangement.spacedBy((-12).dp),
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .graphicsLayer {
                            scaleX = textScale
                            scaleY = textScale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            alpha = textAlpha
                            translationX = textOffset
                        }
                ) {
                    Text(
                        text = displayName,
                        fontSize = 13.sp,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                    Text(
                        text = "Log Out / Switch Profile",
                        fontSize = 10.sp,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }
    }
}