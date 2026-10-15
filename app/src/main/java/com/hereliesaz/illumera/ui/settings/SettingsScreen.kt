package com.hereliesaz.illumera.ui.settings

import com.hereliesaz.illumera.ui.navigation.openNavDrawer
import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.R
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.ui.addons.AddonsScreen
import com.hereliesaz.illumera.ui.watchlist.DebridLibraryViewModel
import com.hereliesaz.illumera.ui.watchlist.DebridLibraryEvent
import com.hereliesaz.illumera.ui.watchlist.DebridLibrarySection
import com.hereliesaz.illumera.ui.queue.QueuePreferencesSettings
import android.widget.Toast
import com.hereliesaz.illumera.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.ui.ExperimentalComposeUiApi

enum class SettingsSection(val label: String, @DrawableRes val iconRes: Int) {
    Personalization("Personalization", R.drawable.personalization_icon),
    Theme("Theme", R.drawable.palette_icon),
    Dashboard("Screen Editor", R.drawable.home_icon),
    Watchlist("Watchlist", R.drawable.watchlist_icon),
    Playback("Playback", R.drawable.playback_icon),
    SourcePreferences("Sort & Filter", R.drawable.source_preferences_icon),
    Addons("Addons", R.drawable.puzzle_icon),
    Integrations("Integrations", R.drawable.integrations_icon),
    About("About", R.drawable.info_icon)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    currentProfile: ProfileEntity?,
    onBack: () -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    onDashboardChanged: () -> Unit = {},
    onPlayResolvedStream: (id: String, url: String, title: String) -> Unit = { _, _, _ -> },
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var selectedSection by remember { mutableStateOf(SettingsSection.Personalization) }
    var displayedSection by remember { mutableStateOf(SettingsSection.Personalization) }
    var isContentFocused by remember { mutableStateOf(false) }
    var isTransitioning by remember { mutableStateOf(false) } // LOCK: prevent re-entry
    // Below this width the side-by-side sidebar+content split wraps section
    // labels one letter per line, so narrow (phone) screens instead get a
    // full-width section list with the content sliding over it, like the
    // details screen's sources panel (GlassSidebarScaffold).
    val isCompact = LocalConfiguration.current.screenWidthDp < 600
    var contentPanelOpen by remember { mutableStateOf(false) }

    val sidebarListRequester = remember { FocusRequester() }
    val contentPaneRequester = remember { FocusRequester() }
    val itemRequesters = remember { SettingsSection.entries.associateWith { FocusRequester() } }

    val scope = rememberCoroutineScope()

    // DEBOUNCE LOGIC (Only for Scrolling)
    LaunchedEffect(selectedSection) {
        delay(300)
        displayedSection = selectedSection
    }

    val isTopNav = currentProfile?.navPosition == "top"

    // Opening Settings focuses the selected section (entryRequester follows it); focus lost
    // under a sub-page's refresh goes back there too.
    val focusMemory = rememberFocusMemory()
    RestoreFocusOnResume(memory = focusMemory, fallback = entryRequester)

    // BACK LOGIC
    // Settings is a main screen: Back first unwinds everything inside it (sub-screens'
    // own BackHandlers are composed later and win; then close the phone content panel,
    // then leave the content pane for the section list). From the section list this handler
    // stands down and the main-root Back (MainRootBackHandler) opens the menu.
    BackHandler(enabled = (isCompact && contentPanelOpen) || isContentFocused || isTransitioning) {
        when {
            isCompact && contentPanelOpen -> {
                contentPanelOpen = false
                itemRequesters[selectedSection]?.requestFocus()
            }
            isContentFocused -> itemRequesters[selectedSection]?.requestFocus()
            // Mid-transition into a section: its content is about to take focus.
            else -> Unit
        }
    }
    
    // Animated padding values for smooth transition when nav mode changes
    val rowStartPadding by animateDpAsState(
        targetValue = if (isTopNav) 0.dp else 80.dp,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "rowStartPadding"
    )
    val colTopPadding by animateDpAsState(
        targetValue = if (isTopNav) 70.dp else 40.dp,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "colTopPadding"
    )
    val contentTopPadding by animateDpAsState(
        targetValue = if (isTopNav) 70.dp else 40.dp,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "contentTopPadding"
    )
    val colStartPadding by animateDpAsState(
        targetValue = if (isTopNav) 50.dp else 32.dp,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "colStartPadding"
    )

    // FUNCTION: Enter Content Logic (with lock)
    fun enterContent(section: SettingsSection) {
        if (isTransitioning) return // BLOCKED
        isTransitioning = true
        selectedSection = section
        displayedSection = section
        contentPanelOpen = true
        scope.launch {
            try {
                delay(400) // Wait for animation
                runCatching { contentPaneRequester.requestFocus() }
            } finally {
                isTransitioning = false // UNLOCK
            }
        }
    }

    val sidebarContent: @Composable () -> Unit = {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp
            ),
            color = Color.White,
            modifier = Modifier.padding(bottom = 32.dp, start = 16.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .focusRequester(sidebarListRequester)
                .padding(top = 0.dp, bottom = 10.dp)
        ) {
            SettingsSection.entries.forEach { section ->
                key(section) {
                    val isSelected = selectedSection == section
                    val isFirstSection = section == SettingsSection.entries.first()

                    val focusModifier = Modifier
                        .focusRequester(itemRequesters[section]!!)
                        .then(if (isSelected) Modifier.focusRequester(entryRequester) else Modifier)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown) {
                                when (it.key) {
                                    Key.DirectionLeft -> {
                                        if (!isTransitioning) {
                                            drawerRequester.openNavDrawer()
                                        }
                                        true
                                    }
                                    // Back is left to the screen's BackHandler, which
                                    // unwinds panels before opening the side menu.
                                    Key.DirectionUp -> {
                                        // Up on first section -> go to drawer/topnav (ONLY in top nav mode)
                                        if (isFirstSection && !isTransitioning && isTopNav) {
                                            drawerRequester.requestFocus()
                                            true
                                        } else false
                                    }
                                    Key.DirectionRight, Key.DirectionCenter, Key.Enter -> {
                                        enterContent(section)
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }

                    SettingsSidebarItem(
                        label = section.label,
                        iconRes = section.iconRes,
                        isSelected = isSelected,
                        modifier = focusModifier,
                        onClick = { if (!isTransitioning) enterContent(section) }, // GATED
                        onFocus = { selectedSection = section }
                    )
                }
            }
        }
    }

    val contentPane: @Composable () -> Unit = {
        AnimatedContent(
            targetState = displayedSection,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            label = "SettingsContent"
        ) { target ->
            when (target) {
                SettingsSection.Personalization -> {
                    PersonalizationSettings(
                        currentProfile = currentProfile,
                        viewModel = viewModel,
                        onGoBack = { itemRequesters[selectedSection]?.requestFocus() }
                    )
                }
                SettingsSection.Theme -> {
                    ThemeSettings(
                        currentProfile = currentProfile,
                        onGoBack = { itemRequesters[selectedSection]?.requestFocus() },
                        isTopNav = isTopNav
                    )
                }
                SettingsSection.Dashboard -> {
                    DashboardEditorScreen(
                        // Home cache invalidation happens when the editor leaves composition
                        // (any exit path), and only if the dashboard was modified.
                        onBack = { runCatching { itemRequesters[selectedSection]?.requestFocus() } },
                        onDashboardChanged = onDashboardChanged,
                        isTopNav = isTopNav,
                        currentProfile = currentProfile
                    )
                }
                SettingsSection.Watchlist -> {
                    WatchlistSettings(currentProfile = currentProfile, onPlayResolvedStream = onPlayResolvedStream)
                }
                SettingsSection.Playback -> {
                    PlaybackSettings(
                        currentProfile = currentProfile,
                        viewModel = viewModel,
                        onGoBack = { itemRequesters[selectedSection]?.requestFocus() }
                    )
                }
                SettingsSection.SourcePreferences -> {
                    SourcePreferencesSettings(
                        currentProfile = currentProfile,
                        viewModel = viewModel,
                        onGoBack = { itemRequesters[selectedSection]?.requestFocus() }
                    )
                }
                SettingsSection.Addons -> {
                    AddonsScreen(
                        onBack = { itemRequesters[selectedSection]?.requestFocus() },
                        isTopNav = isTopNav
                    )
                }
                SettingsSection.Integrations -> {
                    IntegrationsScreen(
                        onBack = { itemRequesters[selectedSection]?.requestFocus() }
                    )
                }
                SettingsSection.About -> {
                    AboutSettings(
                        onGoBack = { itemRequesters[selectedSection]?.requestFocus() }
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusMemoryRoot(focusMemory)
    ) {
        if (isCompact) {
            // --- PHONE / NARROW LAYOUT ---
            // Full-width section list; the content pane slides in from the
            // right over it (same treatment as the details screen's sources
            // panel) instead of squeezing both into a side-by-side row.
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(top = colTopPadding, start = colStartPadding, end = 16.dp)
                        .onFocusChanged { if (it.hasFocus) { isContentFocused = false } }
                ) {
                    sidebarContent()
                }

                if (contentPanelOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                contentPanelOpen = false
                                itemRequesters[selectedSection]?.requestFocus()
                            }
                    )
                }

                AnimatedVisibility(
                    visible = contentPanelOpen,
                    enter = slideInHorizontally(tween(300)) { it },
                    exit = slideOutHorizontally(tween(300)) { it },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .focusProperties {
                                if (isTopNav) {
                                    up = FocusRequester.Cancel
                                }
                            }
                            .focusGroup()
                            .focusRequester(contentPaneRequester)
                            .onFocusChanged { if (it.hasFocus) { isContentFocused = true } }
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(top = contentTopPadding, start = 24.dp, end = 24.dp)) {
                            contentPane()
                        }
                    }
                }
            }
        } else {
            // --- TV / WIDE LAYOUT ---
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = rowStartPadding)
            ) {
                // --- LEFT SIDEBAR ---
                Column(
                    modifier = Modifier
                        .weight(0.28f)
                        .fillMaxHeight()
                        .padding(top = colTopPadding, start = colStartPadding, end = 16.dp)
                        .onFocusChanged { if (it.hasFocus) { isContentFocused = false } }
                ) {
                    sidebarContent()
                }

                // --- RIGHT CONTENT ---
                Box(
                    modifier = Modifier
                        .weight(0.72f) // Expanded content area
                        .fillMaxHeight()
                        .focusProperties {
                            if (isTopNav) {
                                up = FocusRequester.Cancel
                            }
                        }
                        .focusGroup()
                        .focusRequester(contentPaneRequester)
                        .onFocusChanged { if (it.hasFocus) { isContentFocused = true } }
                ) {
                    // INCREASED PADDING: Gutter 64dp (Sidebar ends at weight 0.28, this starts at 0 without extra padding logic, so we add start padding here)
                    Column(modifier = Modifier.fillMaxSize().padding(top = contentTopPadding, start = 64.dp, end = 80.dp)) {
                        contentPane()
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSidebarItem(
    label: String,
    @DrawableRes iconRes: Int,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocus()
    }

    // Visual logic (like VoidTabBtn):
    // - If focused: full highlight (white text)
    // - If selected but not focused: partial highlight (white text)
    val showHighlight = isFocused || isSelected
    
    val contentColor by animateColorAsState(
        if (showHighlight) Color.White else Color.White.copy(0.4f), 
        label = "Content"
    )
    
    // Scale only when focused
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .then(modifier)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes), 
            contentDescription = null, 
            tint = contentColor, 
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label, 
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                fontWeight = if (showHighlight) FontWeight.SemiBold else FontWeight.Normal
            ), 
            color = contentColor
        )
    }
}
@Composable
private fun WatchlistSettings(
    currentProfile: ProfileEntity?,
    onPlayResolvedStream: (id: String, url: String, title: String) -> Unit,
    debridViewModel: DebridLibraryViewModel = hiltViewModel()
) {
    val debridProvider by debridViewModel.connectedProvider.collectAsState()
    val debridState by debridViewModel.uiState.collectAsState()
    val latestOnPlay by rememberUpdatedState(onPlayResolvedStream)
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(debridViewModel) {
        onDispose { debridViewModel.onScreenDisposed() }
    }
    LaunchedEffect(debridViewModel) {
        debridViewModel.events.collect { event ->
            when (event) {
                is DebridLibraryEvent.StreamResolved ->
                    latestOnPlay(event.id, event.url, event.name)
                is DebridLibraryEvent.Error ->
                    Toast.makeText(toastContext, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("illumera_watchlist_ui", 0) }
    var defaultSort by remember(currentProfile?.id) {
        mutableStateOf(prefs.getString("sort_${currentProfile?.id}", "recent") ?: "recent")
    }
    val modes = listOf(
        "recent" to "Recent", "added" to "Recently added",
        "az" to "A–Z", "za" to "Z–A",
        "lastwatched" to "Last watched", "watched" to "Watched",
        "unwatched" to "Not watched", "mostwatched" to "Most watched"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Watchlist", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("Suggested Next appears first, then Queue, then the Library grid.",
            style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "queue_preferences") {
                Text("Queue & suggestions", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(8.dp))
                QueuePreferencesSettings()
                Spacer(Modifier.height(14.dp))
            }
            item(key = "default_library_sort_header") {
                Text("Default Library sorting", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            items(modes) { mode ->
                val selected = defaultSort == mode.first
                Text(
                    text = "${if (selected) "✓  " else ""}${mode.second}",
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = if (selected) 0.13f else 0.05f))
                        .clickable {
                            defaultSort = mode.first
                            prefs.edit().putString("sort_${currentProfile?.id}", mode.first).apply()
                        }
                        .focusable()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
            if (debridProvider != null) {
                item(key = "watchlist_cloud_storage") {
                    Spacer(Modifier.height(20.dp))
                    DebridLibrarySection(
                        provider = debridProvider,
                        state = debridState,
                        startPadding = 0.dp,
                        onPlay = { debridViewModel.play(it) },
                        onDelete = { debridViewModel.delete(it) }
                    )
                }
            }
        }
    }
}
