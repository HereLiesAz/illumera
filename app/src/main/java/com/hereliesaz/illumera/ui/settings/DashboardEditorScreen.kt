package com.hereliesaz.illumera.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.hereliesaz.illumera.ui.util.touchClick
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.HubRowEntity
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.domain.DashboardTab
import com.hereliesaz.illumera.domain.HeroConfig
import com.hereliesaz.illumera.domain.HubShape
import com.hereliesaz.illumera.domain.heroFor
import com.hereliesaz.illumera.domain.layoutFor
import com.hereliesaz.illumera.domain.withHero
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.addons.VoidDialog
import com.hereliesaz.illumera.ui.addons.VoidInput
import com.hereliesaz.illumera.ui.home.CreateHubDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardEditorScreen(
    onBack: () -> Unit,
    isTopNav: Boolean = false,
    currentProfile: ProfileEntity? = null,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val configs by viewModel.configs.collectAsState()
    val hubRows by viewModel.hubRows.collectAsState()
    var selectedTab by remember { mutableStateOf("home") }
    var focusedTab by remember { mutableStateOf("home") }

    var dialogState by remember { mutableStateOf<DialogState>(DialogState.None) }

    var reorderingConfig by remember { mutableStateOf<CatalogConfigEntity?>(null) }
    var reorderingHub by remember { mutableStateOf<HubRowEntity?>(null) }

    var pendingFocusKey by remember { mutableStateOf<String?>(null) }
    var focusTabAfterRemoval by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val homeTabReq = remember { FocusRequester() }
    val moviesTabReq = remember { FocusRequester() }
    val seriesTabReq = remember { FocusRequester() }

    LaunchedEffect(focusTabAfterRemoval) {
        if (focusTabAfterRemoval) {
            delay(50)
            runCatching {
                when (selectedTab) {
                    "home" -> homeTabReq.requestFocus()
                    "movies" -> moviesTabReq.requestFocus()
                    "series" -> seriesTabReq.requestFocus()
                }
            }
            focusTabAfterRemoval = false
        }
    }

    val goBackModifier = Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
            onBack()
            true
        } else false
    }

    val editorItems = remember(configs, hubRows, selectedTab) {
        viewModel.getEditorItems(selectedTab)
    }

    val availableToAdd = remember(configs, hubRows, selectedTab) {
        val unusedConfigs = configs.filter {
            val isUsed = when (selectedTab) {
                "home" -> it.showInHome
                "movies" -> it.showInMovies
                "series" -> it.showInSeries
                else -> true
            }
            !isUsed
        }.map { EditorListItem.CategoryItem(it, 0) }

        val unusedHubs = hubRows.filter {
            val isUsed = when (selectedTab) {
                "home" -> it.hub.showInHome
                "movies" -> it.hub.showInMovies
                "series" -> it.hub.showInSeries
                else -> true
            }
            !isUsed
        }.map { EditorListItem.HubRowItem(it.hub, it.items, 0) }

        (unusedConfigs + unusedHubs).sortedBy {
            when (it) {
                is EditorListItem.CategoryItem -> it.config.addonName
                is EditorListItem.HubRowItem -> it.hub.title
            }
        }
    }

    val tabLabel = when (selectedTab) {
        "home" -> "Home"
        "movies" -> "Movies"
        "series" -> "Series"
        else -> ""
    }

    val isReorderActive = reorderingConfig != null || reorderingHub != null

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // --- HEADER ---
            Text(
                "Home Screen Editor",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                ),
                color = Color.White
            )
            Text(
                "Reorder categories or hide unwanted lists.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = Color.White.copy(0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // --- TABS + ACTION BUTTONS ROW ---
            val isAnyTabFocused = remember { mutableStateOf(false) }

            val upBlockModifier = Modifier.onPreviewKeyEvent {
                if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
                    true
                } else false
            }
            val controlButtonSpacing = 8.dp
            val heroToControlsGap = controlButtonSpacing
            val tabButtonWidth = if (isTopNav) 100.dp else 92.dp
            val addButtonWidth = 108.dp
            val newHubButtonWidth = 140.dp
            val topRightControlsWidth = addButtonWidth + controlButtonSpacing + newHubButtonWidth
            // Manual optical tuning per nav mode.
            val topNavSimpleWidthOffset = (-1).dp
            val topNavCinematicWidthOffset = (-8).dp
            val leftNavCinematicWidthOffset = (-42).dp
            val simpleButtonWidth = addButtonWidth + if (isTopNav) topNavSimpleWidthOffset else 0.dp
            val cinematicButtonWidth = newHubButtonWidth + if (isTopNav) topNavCinematicWidthOffset else leftNavCinematicWidthOffset
            val layoutControlsWidth = simpleButtonWidth + controlButtonSpacing + cinematicButtonWidth

            // The tab row + action buttons need ~530dp combined (fixed optically-tuned
            // widths); that overflows a phone-compact Settings pane (~312dp available),
            // so let it scroll horizontally there instead of overlapping.
            val isCompactDashboardEditor = LocalConfiguration.current.screenWidthDp < 600
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isCompactDashboardEditor) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                // SpaceBetween needs a bounded width to compute a gap; under
                // horizontalScroll the row is measured with relaxed/unbounded width, so
                // use a fixed gap there instead — SpaceBetween otherwise pushes the
                // trailing action buttons off to an enormous, effectively-infinite offset.
                horizontalArrangement = if (isCompactDashboardEditor) Arrangement.spacedBy(24.dp) else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.onFocusChanged { isAnyTabFocused.value = it.hasFocus }) {
                    VoidTabBtn(
                        label = "Home",
                        isSelected = selectedTab == "home",
                        isTabRowFocused = isAnyTabFocused.value,
                        onClick = { selectedTab = "home" },
                        modifier = Modifier
                            .width(tabButtonWidth)
                            .focusRequester(homeTabReq)
                            .onFocusChanged { if (it.isFocused) focusedTab = "home" }
                            .then(goBackModifier)
                            .then(upBlockModifier)
                    )

                    VoidTabBtn(
                        label = "Movies",
                        isSelected = selectedTab == "movies",
                        isTabRowFocused = isAnyTabFocused.value,
                        onClick = { selectedTab = "movies" },
                        modifier = Modifier
                            .width(tabButtonWidth)
                            .focusRequester(moviesTabReq)
                            .onFocusChanged { if (it.isFocused) focusedTab = "movies" }
                            .then(upBlockModifier)
                    )

                    VoidTabBtn(
                        label = "Series",
                        isSelected = selectedTab == "series",
                        isTabRowFocused = isAnyTabFocused.value,
                        onClick = { selectedTab = "series" },
                        modifier = Modifier
                            .width(tabButtonWidth)
                            .focusRequester(seriesTabReq)
                            .onFocusChanged { if (it.isFocused) focusedTab = "series" }
                            .then(upBlockModifier)
                    )
                }

                Row(
                    modifier = Modifier.width(topRightControlsWidth),
                    horizontalArrangement = Arrangement.spacedBy(controlButtonSpacing)
                ) {
                    VoidCompactActionButton(
                        label = "Add",
                        onClick = { dialogState = DialogState.AddToTab(selectedTab) },
                        modifier = Modifier
                            .width(addButtonWidth)
                            .then(upBlockModifier)
                    )
                    VoidCompactActionButton(
                        label = "New Hub",
                        onClick = { dialogState = DialogState.CreateHub },
                        modifier = Modifier
                            .width(newHubButtonWidth)
                            .then(upBlockModifier)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- PER-TAB LAYOUT + HERO CONTROLS ---
            if (currentProfile != null) {
                val dashboardTab = DashboardTab.fromString(selectedTab)
                val currentLayout = currentProfile.layoutFor(dashboardTab)
                val isSimple = currentLayout == "simple"
                val heroConfig = currentProfile.heroFor(dashboardTab)
                val heroLabel = if (heroConfig.categoryId != null) {
                    val cat = configs.find { it.uniqueId == heroConfig.categoryId }
                    val catName = cat?.customTitle ?: cat?.catalogName ?: cat?.addonName ?: "Unknown"
                    val catType = cat?.catalogType?.replaceFirstChar { it.uppercase() } ?: ""
                    if (cat != null) "Hero Carousel · $catName - $catType" else "Hero Carousel · Unknown"
                } else {
                    "Hero Carousel · Disabled"
                }
                val heroSubtitle = when {
                    heroConfig.categoryId != null -> "${heroConfig.posterCount} posters"
                    isSimple -> "Tap to configure"
                    else -> "Switch to Simple to configure"
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    VoidEditorRow(
                        title = heroLabel,
                        subtitle = heroSubtitle,
                        isEnabled = heroConfig.categoryId != null && isSimple,
                        onClick = {
                            if (isSimple) {
                                dialogState = DialogState.HeroCarouselConfig(dashboardTab)
                            }
                        },
                        interactive = isSimple,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = layoutControlsWidth + heroToControlsGap)
                            .then(if (!isSimple) Modifier.alpha(0.8f) else Modifier)
                            .then(if (isSimple) goBackModifier else Modifier)
                    )

                    Row(
                        modifier = Modifier
                            .width(layoutControlsWidth)
                            .align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(controlButtonSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LayoutOptionItem(
                            label = "Simple",
                            onClick = { viewModel.updateTabLayout(currentProfile.id, dashboardTab, "simple") },
                            isSelected = isSimple,
                            modifier = Modifier
                                .width(simpleButtonWidth)
                        )
                        LayoutOptionItem(
                            label = "Cinematic",
                            onClick = { viewModel.updateTabLayout(currentProfile.id, dashboardTab, "cinematic") },
                            isSelected = !isSimple,
                            modifier = Modifier
                                .width(cinematicButtonWidth)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

            }

            // --- REORDER BANNER ---
            if (isReorderActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(0.15f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        "Reorder mode  ·  ▲▼ move  ·  OK done  ·  Back cancel",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // --- SECTION LABEL ---
            Text(
                "$tabLabel rows",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(0.5f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // --- LIST ---
            if (editorItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(0.03f))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No rows on $tabLabel",
                            color = Color.White.copy(0.5f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(12.dp))
                        VoidCompactActionButton(
                            label = "Add Row",
                            onClick = { dialogState = DialogState.AddToTab(selectedTab) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 50.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(
                        items = editorItems,
                        key = { _, item -> item.key }
                    ) { index, item ->
                        when (item) {
                            is EditorListItem.HubRowItem -> {
                                val isReordering = (reorderingHub?.id == item.hub.id)
                                val itemRequester = remember { FocusRequester() }

                                LaunchedEffect(isReordering, item.order) {
                                    if (isReordering) {
                                        delay(50)
                                        itemRequester.requestFocus()
                                    }
                                }

                                LaunchedEffect(pendingFocusKey) {
                                    if (pendingFocusKey == item.key) {
                                        delay(50)
                                        runCatching { itemRequester.requestFocus() }
                                        pendingFocusKey = null
                                    }
                                }

                                VoidHubEditorItem(
                                    hub = item.hub,
                                    itemCount = item.items.size,
                                    isReordering = isReordering,
                                    focusRequester = itemRequester,
                                    modifier = goBackModifier,
                                    onClick = {
                                        if (isReordering) {
                                            reorderingHub = null
                                            showSnackbar("Reorder complete")
                                        } else {
                                            dialogState = DialogState.ManageHub(item)
                                        }
                                    },
                                    onMoveUp = {
                                        if (index > 0) {
                                            viewModel.moveEditorItem(item, -1, selectedTab)
                                            val target = index - 1
                                            scope.launch { delay(50); listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
                                        }
                                    },
                                    onMoveDown = {
                                        if (index < editorItems.size - 1) {
                                            viewModel.moveEditorItem(item, 1, selectedTab)
                                            val target = index + 1
                                            scope.launch { delay(50); listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
                                        }
                                    }
                                )
                            }

                            is EditorListItem.CategoryItem -> {
                                val config = item.config
                                val isReordering = (reorderingConfig?.uniqueId == config.uniqueId)
                                val itemRequester = remember { FocusRequester() }

                                LaunchedEffect(isReordering) {
                                    if (isReordering) {
                                        delay(50)
                                        itemRequester.requestFocus()
                                    }
                                }

                                LaunchedEffect(pendingFocusKey) {
                                    if (pendingFocusKey == item.key) {
                                        delay(50)
                                        runCatching { itemRequester.requestFocus() }
                                        pendingFocusKey = null
                                    }
                                }

                                VoidEditorItem(
                                    config = config,
                                    isReordering = isReordering,
                                    focusRequester = itemRequester,
                                    modifier = goBackModifier,
                                    onClick = {
                                        if (isReordering) {
                                            reorderingConfig = null
                                            showSnackbar("Reorder complete")
                                        } else {
                                            dialogState = DialogState.ManageCategory(config)
                                        }
                                    },
                                    onMoveUp = {
                                        if (index > 0) {
                                            viewModel.moveEditorItem(item, -1, selectedTab)
                                            val target = index - 1
                                            scope.launch { delay(50); listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
                                        }
                                    },
                                    onMoveDown = {
                                        if (index < editorItems.size - 1) {
                                            viewModel.moveEditorItem(item, 1, selectedTab)
                                            val target = index + 1
                                            scope.launch { delay(50); listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- SNACKBAR ---
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            snackbar = { data ->
                Snackbar(
                    containerColor = Color.White.copy(0.12f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        )
    }

    // --- DIALOGS (Single DialogState) ---

    when (val state = dialogState) {
        DialogState.None -> {}

        is DialogState.ManageCategory -> {
            val config = state.config
            val catalogDefault = if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId}"
            val title = config.customTitle ?: catalogDefault
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

            VoidDialog(onDismissRequest = { dialogState = DialogState.None }, title = "Manage Category") {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                VoidButton("Move Category", {
                    reorderingConfig = config
                    dialogState = DialogState.None
                    showSnackbar("Use ▲▼ to reorder, OK when done")
                }, Modifier.fillMaxWidth(), focusRequester = focusRequester)

                Spacer(Modifier.height(12.dp))
                VoidButton("Rename", {
                    dialogState = DialogState.RenameCategory(config, title)
                }, Modifier.fillMaxWidth())

                Spacer(Modifier.height(12.dp))
                VoidButton("Layout Settings", {
                    dialogState = DialogState.LayoutSettings(config)
                }, Modifier.fillMaxWidth())

                Spacer(Modifier.height(12.dp))
                VoidButton("Hide from $tabLabel", {
                    val removedKey = "cat:${config.uniqueId}"
                    val idx = editorItems.indexOfFirst { it.key == removedKey }
                    val neighborKey = when {
                        idx > 0 -> editorItems[idx - 1].key
                        idx >= 0 && editorItems.size > 1 -> editorItems[idx + 1].key
                        else -> null
                    }
                    if (neighborKey != null) pendingFocusKey = neighborKey else focusTabAfterRemoval = true
                    viewModel.removeItemFromTab(EditorListItem.CategoryItem(config, 0), selectedTab)
                    dialogState = DialogState.None
                    showSnackbar("Hidden from $tabLabel")
                }, Modifier.fillMaxWidth())

                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    VoidButton("Close", { dialogState = DialogState.None }, Modifier.width(120.dp))
                }
            }
        }

        is DialogState.RenameCategory -> {
            var newName by remember { mutableStateOf(state.currentName) }
            val inputFocusRequester = remember { FocusRequester() }
            val saveFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(100); inputFocusRequester.requestFocus() }

            VoidDialog(onDismissRequest = { dialogState = DialogState.None }, title = "Rename") {
                VoidInput(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = "Category Name",
                    modifier = Modifier.focusRequester(inputFocusRequester),
                    onDone = { saveFocusRequester.requestFocus() }
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VoidButton("Cancel", { dialogState = DialogState.None }, Modifier.weight(1f))
                    VoidButton("Save", {
                        viewModel.renameCatalog(state.config, newName)
                        dialogState = DialogState.None
                        showSnackbar("Renamed")
                    }, Modifier.weight(1f), isPrimary = true, focusRequester = saveFocusRequester)
                }
            }
        }

        is DialogState.LayoutSettings -> {
            val config = state.config
            val catalogDefault = if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId}"
            val title = config.customTitle ?: catalogDefault
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

            var isGridViewEnabled by remember(config) { mutableStateOf(config.isInfiniteLoopEnabled) }
            var itemCount by remember(config) { mutableFloatStateOf(config.visibleItemCount.toFloat()) }
            var isInfiniteScrolling by remember(config) { mutableStateOf(config.isInfiniteScrollingEnabled) }

            VoidDialog(onDismissRequest = { dialogState = DialogState.None }, title = "Layout Settings") {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                VoidCheckboxRow(
                    label = "Enable Grid View",
                    isChecked = isGridViewEnabled,
                    onCheckedChange = { isGridViewEnabled = it },
                    focusRequester = focusRequester
                )

                if (isGridViewEnabled) {
                    Spacer(Modifier.height(16.dp))
                    VoidCheckboxRow(
                        label = "Enable Infinite Looping",
                        isChecked = isInfiniteScrolling,
                        onCheckedChange = { isInfiniteScrolling = it }
                    )

                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Items before View More: ${itemCount.toInt()}",
                        color = Color.White.copy(0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    VoidSlider(
                        value = itemCount,
                        onValueChange = { itemCount = it },
                        valueRange = 5f..50f,
                        steps = 44
                    )
                }

                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VoidButton("Cancel", { dialogState = DialogState.None }, Modifier.weight(1f))
                    VoidButton("Save", {
                        viewModel.updateLayoutSettings(config, isGridViewEnabled, itemCount.toInt(), isInfiniteScrolling)
                        dialogState = DialogState.None
                        showSnackbar("Layout updated")
                    }, Modifier.weight(1f), isPrimary = true)
                }
            }
        }

        is DialogState.AddToTab -> {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

            VoidDialog(
                onDismissRequest = { dialogState = DialogState.None },
                title = "Add to ${state.tab.uppercase()}"
            ) {
                if (availableToAdd.isEmpty()) {
                    Text("No hidden items found.", color = Color.Gray)
                    Spacer(Modifier.height(24.dp))
                    VoidButton("Close", { dialogState = DialogState.None }, Modifier.fillMaxWidth(), focusRequester = focusRequester)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(availableToAdd) { index, item ->
                            val itemTitle = when (item) {
                                is EditorListItem.CategoryItem -> item.config.customTitle
                                    ?: if (item.config.catalogName != null) "${item.config.catalogName} - ${item.config.catalogType.replaceFirstChar { it.uppercase() }}" else "${item.config.addonName} - ${item.config.catalogId}"
                                is EditorListItem.HubRowItem -> "⬡ ${item.hub.title}"
                            }

                            VoidSimpleItem(
                                text = itemTitle,
                                onClick = {
                                    viewModel.addItemToTab(item, selectedTab)
                                    dialogState = DialogState.None
                                    showSnackbar("Added to $tabLabel")
                                },
                                focusRequester = if (index == 0) focusRequester else null
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    VoidButton("Close", { dialogState = DialogState.None }, Modifier.fillMaxWidth())
                }
            }
        }

        DialogState.CreateHub -> {
            CreateHubDialog(
                categories = configs,
                onDismiss = { dialogState = DialogState.None },
                onCreate = { name, shape, items ->
                    viewModel.createHubRow(name, shape, items, selectedTab)
                    dialogState = DialogState.None
                    showSnackbar("Hub row created")
                }
            )
        }

        is DialogState.ManageHub -> {
            val hubItem = state.hubItem
            var showDeleteHubConfirm by remember(hubItem.hub.id) { mutableStateOf(false) }

            if (!showDeleteHubConfirm) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

                VoidDialog(onDismissRequest = { dialogState = DialogState.None }, title = "Manage Hub Row") {
                Text(
                    hubItem.hub.title,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "${hubItem.items.size} categories · ${hubItem.hub.shape}",
                    color = Color.White.copy(0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                VoidButton("Move Hub Row", {
                    reorderingHub = hubItem.hub
                    dialogState = DialogState.None
                    showSnackbar("Use ▲▼ to reorder, OK when done")
                }, Modifier.fillMaxWidth(), focusRequester = focusRequester)

                Spacer(Modifier.height(12.dp))
                VoidButton("Edit Hub Row", {
                    dialogState = DialogState.EditHub(hubItem)
                }, Modifier.fillMaxWidth())

                Spacer(Modifier.height(12.dp))
                VoidButton("Hide from $tabLabel", {
                    val removedKey = "hub:${hubItem.hub.id}"
                    val idx = editorItems.indexOfFirst { it.key == removedKey }
                    val neighborKey = when {
                        idx > 0 -> editorItems[idx - 1].key
                        idx >= 0 && editorItems.size > 1 -> editorItems[idx + 1].key
                        else -> null
                    }
                    if (neighborKey != null) pendingFocusKey = neighborKey else focusTabAfterRemoval = true
                    viewModel.removeItemFromTab(hubItem, selectedTab)
                    dialogState = DialogState.None
                    showSnackbar("Hidden from $tabLabel")
                }, Modifier.fillMaxWidth())

                Spacer(Modifier.height(12.dp))
                VoidButton("Delete Hub Row", {
                    showDeleteHubConfirm = true
                }, Modifier.fillMaxWidth(), isDestructive = true)

                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    VoidButton("Close", { dialogState = DialogState.None }, Modifier.width(120.dp))
                }
            }
            } else {
                val confirmFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(100); confirmFocusRequester.requestFocus() }

                VoidDialog(
                    onDismissRequest = { showDeleteHubConfirm = false },
                    title = "Delete Hub Row?"
                ) {
                    Text(
                        "Are you sure you want to delete \"${hubItem.hub.title}\"?",
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        VoidButton(
                            "Cancel",
                            { showDeleteHubConfirm = false },
                            Modifier.weight(1f),
                            focusRequester = confirmFocusRequester
                        )
                        VoidButton(
                            "Delete",
                            {
                                val removedKey = "hub:${hubItem.hub.id}"
                                val idx = editorItems.indexOfFirst { it.key == removedKey }
                                val neighborKey = when {
                                    idx > 0 -> editorItems[idx - 1].key
                                    idx >= 0 && editorItems.size > 1 -> editorItems[idx + 1].key
                                    else -> null
                                }
                                if (neighborKey != null) pendingFocusKey = neighborKey else focusTabAfterRemoval = true
                                viewModel.deleteHubRow(hubItem.hub.id)
                                dialogState = DialogState.None
                                showSnackbar("Hub row deleted")
                            },
                            Modifier.weight(1f),
                            isDestructive = true
                        )
                    }
                }
            }
        }

        is DialogState.EditHub -> {
            val hubItem = state.hubItem
            val freshHub = hubRows.find { it.hub.id == hubItem.hub.id }
            val currentHub = freshHub?.hub ?: hubItem.hub
            val currentItems = freshHub?.items?.sortedBy { it.itemOrder } ?: hubItem.items

            var editName by remember(currentHub.id) { mutableStateOf(currentHub.title) }
            var editShape by remember(currentHub.id) {
                mutableStateOf(
                    try {
                        HubShape.valueOf(currentHub.shape)
                    } catch (_: Exception) {
                        HubShape.HORIZONTAL
                    }
                )
            }
            var showAddCategoryToHub by remember { mutableStateOf(false) }
            var selectedManageItem by remember { mutableStateOf<HubRowItemEntity?>(null) }
            var reorderingHubItem by remember { mutableStateOf<HubRowItemEntity?>(null) }

            val focusRequester = remember { FocusRequester() }
            val shapeFocusRequester = remember { FocusRequester() }
            val addCategoryFocusRequester = remember { FocusRequester() }
            val manageCategoriesFocusRequester = remember { FocusRequester() }
            val context = LocalContext.current
            var lastSubDialog by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { delay(100); shapeFocusRequester.requestFocus() }

            val existingCategoryIds = currentItems.map { it.configUniqueId }.toSet()
            val availableForHub = configs.filter { it.uniqueId !in existingCategoryIds }

            var showHubCategoryManager by remember { mutableStateOf(false) }

            // Restore focus when returning from a sub-dialog
            LaunchedEffect(showAddCategoryToHub, showHubCategoryManager, lastSubDialog) {
                if (!showAddCategoryToHub && !showHubCategoryManager && lastSubDialog != null) {
                    delay(100)
                    when (lastSubDialog) {
                        "add" -> addCategoryFocusRequester.requestFocus()
                        "manage" -> manageCategoriesFocusRequester.requestFocus()
                    }
                }
            }

            // Main Content (always rendered — sub-dialogs overlay on top)
            HubEditorContent(
                title = if (reorderingHubItem != null) "Reordering..." else "Edit Hub Row",
                name = editName,
                onNameChange = { editName = it },
                shape = editShape,
                onShapeChange = { editShape = it },
                categoryCount = currentItems.size,
                onAddCategory = { lastSubDialog = "add"; showAddCategoryToHub = true },
                onManageCategories = { lastSubDialog = "manage"; showHubCategoryManager = true },
                onCancel = {
                    if (reorderingHubItem != null) {
                        reorderingHubItem = null
                    } else {
                        dialogState = DialogState.None
                    }
                },
                onSave = {
                    viewModel.renameHubRow(currentHub.id, editName.ifBlank { "Hub Row" })
                    viewModel.changeHubRowShape(currentHub.id, editShape)
                    reorderingHubItem = null
                    dialogState = DialogState.None
                    showSnackbar("Hub row saved")
                },
                saveEnabled = editName.isNotBlank() && currentItems.isNotEmpty(),
                focusRequester = focusRequester,
                shapeFocusRequester = shapeFocusRequester,
                addCategoryFocusRequester = addCategoryFocusRequester,
                manageCategoriesFocusRequester = manageCategoriesFocusRequester
            )

            if (showHubCategoryManager) {
                HubCategoryManagerDialog(
                    initialItems = currentItems,
                    hubShape = editShape,
                    onDismiss = { showHubCategoryManager = false },
                    onSave = { updatedItems ->
                        val newIds = updatedItems.map { it.configUniqueId }.toSet()
                        val oldIds = currentItems.map { it.configUniqueId }.toSet()
                        val removedIds = oldIds - newIds

                        removedIds.forEach { id ->
                            viewModel.removeCategoryFromHubRow(currentHub.id, id)
                        }

                        viewModel.updateHubRowItemsOrder(currentHub.id, updatedItems)

                        updatedItems.forEach { newItem ->
                            val oldItem =
                                currentItems.find { it.configUniqueId == newItem.configUniqueId }
                            if (oldItem != null && newItem.customImageUrl != oldItem.customImageUrl) {
                                viewModel.updateHubItemImage(
                                    currentHub.id,
                                    newItem.configUniqueId,
                                    newItem.customImageUrl
                                )
                            }
                        }

                        showHubCategoryManager = false
                    }
                )
            }

            if (selectedManageItem != null) {
                HubItemManageDialog(
                    item = selectedManageItem!!,
                    onDismiss = { selectedManageItem = null },
                    onRename = { newName ->
                        viewModel.renameHubRowItem(
                            currentHub.id,
                            selectedManageItem!!.configUniqueId,
                            newName
                        )
                    },
                    onStartReorder = {
                        reorderingHubItem = selectedManageItem
                        selectedManageItem = null
                    },
                    onRemove = {
                        viewModel.removeCategoryFromHubRow(
                            currentHub.id,
                            selectedManageItem!!.configUniqueId
                        )
                        selectedManageItem = null
                    },
                    onRemoveImage = {
                        viewModel.updateHubItemImage(
                            currentHub.id,
                            selectedManageItem!!.configUniqueId,
                            null
                        )
                    }
                )
            }

            if (showAddCategoryToHub) {
                val addFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(100); addFocusRequester.requestFocus() }

                VoidDialog(
                    onDismissRequest = { showAddCategoryToHub = false },
                    title = "Add Category to Hub"
                ) {
                    if (availableForHub.isEmpty()) {
                        Text("All categories are already in this hub row.", color = Color.Gray)
                        Spacer(Modifier.height(24.dp))
                        VoidButton(
                            "Close",
                            { showAddCategoryToHub = false },
                            Modifier.fillMaxWidth(),
                            focusRequester = addFocusRequester
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(availableForHub) { index, config ->
                                val configTitle =
                                    config.customTitle ?: if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId}"
                                VoidSimpleItem(
                                    text = configTitle,
                                    onClick = {
                                        viewModel.addCategoryToHubRow(currentHub.id, config)
                                        showAddCategoryToHub = false
                                    },
                                    focusRequester = if (index == 0) addFocusRequester else null
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        VoidButton(
                            "Close",
                            { showAddCategoryToHub = false },
                            Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        is DialogState.HeroCarouselConfig -> {
            if (currentProfile != null) {
                val tab = state.tab
                val heroConfig = currentProfile.heroFor(tab)
                var selectedCategoryId by remember { mutableStateOf(heroConfig.categoryId) }
                var posterCount by remember { mutableFloatStateOf(heroConfig.posterCount.toFloat()) }
                var autoScrollSeconds by remember { mutableFloatStateOf(heroConfig.autoScrollSeconds.toFloat()) }
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

                val visibleConfigs = configs.filter {
                    when (tab) {
                        DashboardTab.HOME -> it.showInHome
                        DashboardTab.MOVIES -> it.showInMovies
                        DashboardTab.SERIES -> it.showInSeries
                    }
                }

                var showCategoryPicker by remember { mutableStateOf(false) }
                var showDisableConfirm by remember(tab) { mutableStateOf(false) }

                if (!showDisableConfirm && !showCategoryPicker) {
                    VoidDialog(
                        onDismissRequest = { dialogState = DialogState.None },
                        title = "Hero Carousel"
                    ) {
                    val selectedCatName = if (selectedCategoryId != null) {
                        val cat = configs.find { it.uniqueId == selectedCategoryId }
                        if (cat != null) {
                            val baseTitle = cat.customTitle ?: cat.catalogName ?: cat.addonName
                            "$baseTitle - ${cat.catalogType.replaceFirstChar { it.uppercase() }}"
                        } else "Unknown"
                    } else "None"

                    Text(
                        "Category",
                        color = Color.White.copy(0.6f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    VoidButton(
                        text = selectedCatName,
                        onClick = { showCategoryPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = focusRequester
                    )

                    Spacer(Modifier.height(20.dp))

                    Text(
                        "Posters: ${posterCount.toInt()}",
                        color = Color.White.copy(0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    VoidSlider(
                        value = posterCount,
                        onValueChange = { posterCount = it },
                        valueRange = 5f..20f,
                        steps = 14
                    )

                    Spacer(Modifier.height(20.dp))

                    val autoScrollLabel = if (autoScrollSeconds.toInt() == 0) "Off" else "${autoScrollSeconds.toInt()}s"
                    Text(
                        "Auto-scroll: $autoScrollLabel",
                        color = Color.White.copy(0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    VoidSlider(
                        value = autoScrollSeconds,
                        onValueChange = { autoScrollSeconds = it },
                        valueRange = 0f..15f,
                        steps = 14
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        VoidButton("Disable", {
                            showDisableConfirm = true
                        }, Modifier.weight(1f), isDestructive = true, enabled = heroConfig.categoryId != null)
                        VoidButton("Save", {
                            viewModel.updateTabHero(
                                currentProfile.id,
                                tab,
                                HeroConfig(selectedCategoryId, posterCount.toInt(), autoScrollSeconds.toInt())
                            )
                            dialogState = DialogState.None
                            showSnackbar("Hero carousel updated")
                        }, Modifier.weight(1f), isPrimary = true, enabled = selectedCategoryId != null)
                    }
                    }
                } else {
                    val confirmFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(100); confirmFocusRequester.requestFocus() }

                    VoidDialog(
                        onDismissRequest = { showDisableConfirm = false },
                        title = "Disable Hero Carousel?"
                    ) {
                        Text(
                            "This will turn off the Hero Carousel for ${tab.name.lowercase().replaceFirstChar { it.uppercase() }}.",
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            VoidButton(
                                "Cancel",
                                { showDisableConfirm = false },
                                Modifier.weight(1f),
                                focusRequester = confirmFocusRequester
                            )
                            VoidButton(
                                "Disable",
                                {
                                    viewModel.updateTabHero(
                                        currentProfile.id,
                                        tab,
                                        HeroConfig(null, 10, 0)
                                    )
                                    dialogState = DialogState.None
                                    showSnackbar("Hero carousel disabled")
                                },
                                Modifier.weight(1f),
                                isDestructive = true
                            )
                        }
                    }
                }

                if (showCategoryPicker && !showDisableConfirm) {
                    val pickerFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(100); pickerFocusRequester.requestFocus() }

                    VoidDialog(
                        onDismissRequest = { showCategoryPicker = false },
                        title = "Select Category"
                    ) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(visibleConfigs) { index, config ->
                                val baseTitle = config.customTitle ?: config.catalogName ?: config.addonName
                                val configTitle = "$baseTitle - ${config.catalogType.replaceFirstChar { it.uppercase() }}"
                                VoidSimpleItem(
                                    text = configTitle,
                                    onClick = {
                                        selectedCategoryId = config.uniqueId
                                        showCategoryPicker = false
                                    },
                                    focusRequester = if (index == 0) pickerFocusRequester else null
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        VoidButton("Cancel", { showCategoryPicker = false }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// --- LOCAL VOID COMPONENTS ---

@Composable
fun VoidEditorItem(
    config: CatalogConfigEntity,
    isReordering: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = config.customTitle ?: if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId.replaceFirstChar { it.uppercase() }}"
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val itemHeight = 56.dp
    val accentColor = MaterialTheme.colorScheme.primary

    val borderColor by animateColorAsState(
        when {
            isReordering -> accentColor
            isFocused -> accentColor
            else -> Color.Transparent
        }
    )
    val borderWidth = if (isReordering || isFocused) 2.dp else 0.dp
    val bgColor = Color.White.copy(0.05f)
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent {
                if (isReordering) {
                    when (it.key) {
                        Key.DirectionUp -> { if (it.type == KeyEventType.KeyDown) onMoveUp(); true }
                        Key.DirectionDown -> { if (it.type == KeyEventType.KeyDown) onMoveDown(); true }
                        else -> false
                    }
                } else false
            }
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (!isReordering) {
                Text(
                    "≡",
                    color = Color.White.copy(if (isFocused) 0.5f else 0.2f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor.copy(0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "Category",
                    color = accentColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Text(
                text = title,
                color = if (isFocused || isReordering) Color.White else Color.White.copy(0.7f),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (isReordering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "▲",
                    color = accentColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .touchClick(onClick = onMoveUp)
                        .padding(8.dp)
                )
                Text(
                    "▼",
                    color = accentColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .touchClick(onClick = onMoveDown)
                        .padding(8.dp)
                )
            }
        } else if (isFocused) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manage", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelSmall)
                Text(" ›", color = Color.White.copy(0.4f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun VoidHubEditorItem(
    hub: HubRowEntity,
    itemCount: Int,
    isReordering: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val itemHeight = 56.dp
    val accentColor = MaterialTheme.colorScheme.primary

    val borderColor by animateColorAsState(
        when {
            isReordering -> accentColor
            isFocused -> accentColor
            else -> Color.Transparent
        }
    )
    val borderWidth = if (isReordering || isFocused) 2.dp else 0.dp
    val bgColor = Color.White.copy(0.05f)
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent {
                if (isReordering) {
                    when (it.key) {
                        Key.DirectionUp -> { if (it.type == KeyEventType.KeyDown) onMoveUp(); true }
                        Key.DirectionDown -> { if (it.type == KeyEventType.KeyDown) onMoveDown(); true }
                        else -> false
                    }
                } else false
            }
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (!isReordering) {
                Text(
                    "≡",
                    color = Color.White.copy(if (isFocused) 0.5f else 0.2f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor.copy(0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "Hub",
                    color = accentColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column {
                Text(
                    text = hub.title,
                    color = if (isFocused || isReordering) Color.White else Color.White.copy(0.7f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$itemCount categories · ${hub.shape}",
                    color = Color.White.copy(0.4f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (isReordering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "▲",
                    color = accentColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .touchClick(onClick = onMoveUp)
                        .padding(8.dp)
                )
                Text(
                    "▼",
                    color = accentColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .touchClick(onClick = onMoveDown)
                        .padding(8.dp)
                )
            }
        } else if (isFocused) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manage", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelSmall)
                Text(" ›", color = Color.White.copy(0.4f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun HubItemEditorRow(
    item: HubRowItemEntity,
    isReordering: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isReordering) {
        if (isReordering) {
            focusRequester.requestFocus()
        }
    }

    val borderColor by animateColorAsState(
        if (isReordering || isFocused) MaterialTheme.colorScheme.primary else Color.Transparent
    )
    val borderWidth = if (isReordering || isFocused) 1.dp else 0.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused || isReordering) Color.White.copy(0.1f) else Color.White.copy(0.03f))
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent {
                if (isReordering) {
                    when (it.key) {
                        Key.DirectionUp -> { if (it.type == KeyEventType.KeyDown) onMoveUp(); true }
                        Key.DirectionDown -> { if (it.type == KeyEventType.KeyDown) onMoveDown(); true }
                        else -> false
                    }
                } else false
            }
            .padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White.copy(0.8f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.customImageUrl != null) {
                Text(
                    text = "Has custom image",
                    color = MaterialTheme.colorScheme.primary.copy(0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (isReordering) {
            Text(
                text = "▲▼",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 12.dp)
            )
        } else if (isFocused) {
            Text(
                text = "Manage ›",
                color = MaterialTheme.colorScheme.primary.copy(0.9f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    }
}

@Composable
fun VoidCompactActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f)
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .height(36.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.07f))
            .border(
                if (isFocused) 2.dp else 1.dp,
                if (isFocused) accentColor else Color.White.copy(0.15f),
                RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label.uppercase(),
            color = if (isFocused) accentColor else Color.White.copy(0.8f),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun VoidTabBtn(
    label: String,
    isSelected: Boolean,
    isTabRowFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val accentColor = MaterialTheme.colorScheme.primary

    val showHighlight = isFocused || (isSelected && !isTabRowFocused)

    val textColor = if (showHighlight) Color.White else Color.White.copy(0.6f)
    val indicatorHeight = if (showHighlight) 2.dp else 0.dp
    val indicatorColor = accentColor

    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f)

    Column(
        modifier = modifier
            .height(40.dp)
            .scale(scale)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (showHighlight) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp
            ),
            color = textColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.width(30.dp).height(indicatorHeight).background(indicatorColor))
    }
}

@Composable
fun VoidSimpleItem(text: String, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White.copy(0.1f) else Color.White.copy(0.05f))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, color = Color.White)
        if (isFocused) Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun VoidCheckboxRow(
    label: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.05f))
            .border(
                if (isFocused || isChecked) 2.dp else 0.dp,
                when {
                    isFocused -> Color.White
                    isChecked -> accentColor
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) {
                onCheckedChange(!isChecked)
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = when {
                isChecked -> accentColor
                isFocused -> Color.White
                else -> Color.White.copy(0.8f)
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Checkbox(
            checked = isChecked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = accentColor,
                uncheckedColor = Color.White.copy(0.5f),
                checkmarkColor = Color.White
            )
        )
    }
}

@Composable
fun VoidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val stepSize = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    val progress = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)

    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VoidSliderButton(
            text = "−",
            enabled = value > valueRange.start,
            onClick = {
                val newValue = (value - stepSize).coerceIn(valueRange)
                onValueChange(newValue)
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor)
            )
        }

        VoidSliderButton(
            text = "+",
            enabled = value < valueRange.endInclusive,
            onClick = {
                val newValue = (value + stepSize).coerceIn(valueRange)
                onValueChange(newValue)
            }
        )
    }
}

@Composable
private fun VoidSliderButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "scale")

    Box(
        modifier = Modifier
            .size(48.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.1f))
            .border(
                if (isFocused) 2.dp else 1.dp,
                when {
                    isFocused && enabled -> accentColor
                    isFocused && !enabled -> Color.White.copy(0.3f)
                    else -> Color.White.copy(0.2f)
                },
                RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { if (enabled) onClick() }
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> Color.White.copy(0.3f)
                isFocused -> accentColor
                else -> Color.White.copy(0.8f)
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
fun LayoutOptionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f)
    val bgColor = Color.White.copy(0.07f)
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isFocused -> Color.White
        else -> Color.White.copy(0.7f)
    }
    val borderColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> MaterialTheme.colorScheme.primary
            else -> Color.White.copy(0.15f)
        }
    )
    val borderWidth = if (isFocused || isSelected) 2.dp else 1.dp

    Box(
        modifier = modifier
            .height(36.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun VoidEditorRow(
    title: String,
    subtitle: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
    interactive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (interactive && isFocused) 1.02f else 1f)
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White.copy(0.1f) else Color.White.copy(0.05f))
            .border(
                if (isFocused) 2.dp else 0.dp,
                if (isFocused) accentColor else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .then(
                if (interactive) {
                    Modifier
                        .clickable(interactionSource = interactionSource, indication = null) { onClick() }
                        .focusable(interactionSource = interactionSource)
                } else {
                    Modifier.focusable(enabled = false, interactionSource = interactionSource)
                }
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = Color.White.copy(0.5f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (isEnabled) accentColor else Color.White.copy(0.3f))
        )
    }
}
