package com.hereliesaz.illumera.ui.addons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.hereliesaz.illumera.ui.util.rememberDialogWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hereliesaz.illumera.data.model.AddonEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class RenameOperation(val transportUrl: String, val currentName: String)

@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    isTopNav: Boolean = false,
    viewModel: AddonsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var urlInput by remember { mutableStateOf("") }
    var renameOp by remember { mutableStateOf<RenameOperation?>(null) }
    var addonToDelete by remember { mutableStateOf<AddonEntity?>(null) }
    var selectedAddon by remember { mutableStateOf<AddonEntity?>(null) }
    var showRemotePaste by remember { mutableStateOf(false) }
    var showCatalogBrowser by remember { mutableStateOf(false) }
    var remoteConfigurationUrl by remember { mutableStateOf<String?>(null) }
    var remoteConfigurationName by remember { mutableStateOf<String?>(null) }
    var reorderingAddon by remember { mutableStateOf<AddonEntity?>(null) }
    val isReorderActive = reorderingAddon != null
    val installButtonFocus = remember { FocusRequester() }
    var pendingFocusUrl by remember { mutableStateOf<String?>(null) }
    var focusInstallAfterDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(focusInstallAfterDelete) {
        if (focusInstallAfterDelete) {
            delay(50)
            runCatching { installButtonFocus.requestFocus() }
            focusInstallAfterDelete = false
        }
    }

    BackHandler(onBack = {
        if (reorderingAddon != null) {
            reorderingAddon = null
        } else {
            onBack()
        }
    })

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner.lifecycle) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collectLatest { event ->
                if (event is AddonEvent.InstallationSuccess) urlInput = ""
            }
        }
    }

    // SHARED MODIFIER: Go back on Left Arrow
    val goBackModifier = Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
            onBack()
            true
        } else false
    }
    
    // Block Up navigation when top nav is active
    val upBlockModifier = Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
            true // Consume the event to block focus escape
        } else false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // HEADER
        Text(
            "Addon Manager",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            color = Color.White
        )
        Text(
            "Install and manage your Stremio addons.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Color.White.copy(0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            VoidButton(
                text = "Browse Stremio Addons",
                onClick = {
                    showCatalogBrowser = true
                    viewModel.loadCatalog()
                },
                modifier = Modifier.width(240.dp).then(goBackModifier).then(upBlockModifier)
            )
            Text(
                "Official and Community addon catalogs",
                color = Color.White.copy(0.5f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 1. INSTALLATION BAR
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            VoidIconButton(
                icon = Icons.Default.QrCode2,
                contentDescription = "Remote Paste",
                onClick = {
                    remoteConfigurationUrl = null
                    remoteConfigurationName = null
                    showRemotePaste = true
                },
                modifier = goBackModifier.then(upBlockModifier)
            )
            Spacer(modifier = Modifier.width(12.dp))
            VoidInput(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = "https://...",
                modifier = Modifier.weight(1f).then(upBlockModifier),
                onDone = {
                    if (urlInput.isNotBlank()) {
                        installButtonFocus.requestFocus()
                    }
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            VoidButton(
                text = "INSTALL",
                onClick = { viewModel.prepareInstall(urlInput) },
                modifier = Modifier.width(140.dp).then(upBlockModifier),
                enabled = urlInput.isNotBlank(),
                focusRequester = installButtonFocus
            )
        }

        val connectedDebridProvider by viewModel.connectedDebridProvider.collectAsState()
        if (connectedDebridProvider != null) {
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            val context = androidx.compose.ui.platform.LocalContext.current
            Spacer(Modifier.height(12.dp))
            Text(
                "Copy ${connectedDebridProvider!!.displayName} key",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clickable {
                        val key = viewModel.getDebridApiKeyForClipboard()
                        if (key != null) {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(key))
                            android.widget.Toast.makeText(context, "Copied — paste it into this addon's config page", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(vertical = 4.dp)
            )
            Text(
                "Known addons (like Torrentio) get it added automatically on install.",
                color = Color.White.copy(0.4f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        if (state.isLoading) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(0.1f)
            )
        }

        if (state.error != null) {
            Text(
                text = state.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isReorderActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    "Reorder mode  ·  ▲▼ move  ·  OK done  ·  Back cancel",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            Text(
                "INSTALLED ADDONS",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White.copy(0.6f)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 2. ADDON LIST
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 50.dp)
        ) {
            itemsIndexed(state.addons, key = { _, addon -> addon.transportUrl }) { index, addon ->
                val isReordering = reorderingAddon?.transportUrl == addon.transportUrl
                val itemFocusRequester = remember { FocusRequester() }

                LaunchedEffect(pendingFocusUrl) {
                    if (pendingFocusUrl == addon.transportUrl) {
                        delay(50)
                        runCatching { itemFocusRequester.requestFocus() }
                        pendingFocusUrl = null
                    }
                }

                VoidAddonItem(
                    addon = addon,
                    isReordering = isReordering,
                    focusRequester = itemFocusRequester,
                    onClick = {
                        if (isReordering) {
                            reorderingAddon = null
                        } else if (!isReorderActive) {
                            selectedAddon = addon
                        }
                    },
                    onMoveUp = {
                        viewModel.moveAddon(addon, -1)
                        val target = index - 1
                        scope.launch { delay(50); listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
                    },
                    onMoveDown = {
                        viewModel.moveAddon(addon, 1)
                        val target = index + 1
                        scope.launch { delay(50); listState.animateScrollToItem((target - 1).coerceAtLeast(0)) }
                    },
                    modifier = goBackModifier.then(
                        if (isReordering) Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                                reorderingAddon = null
                                true
                            } else false
                        } else Modifier
                    )
                )
            }
        }
    }

    // --- DIALOGS (Void Style) ---

    if (showCatalogBrowser) {
        AddonCatalogDialog(
            state = state,
            onDismissRequest = { showCatalogBrowser = false },
            onLoad = { viewModel.loadCatalog() },
            onSourceSelected = viewModel::selectCatalogSource,
            onRetry = viewModel::retryCatalog,
            onInstall = { item ->
                showCatalogBrowser = false
                viewModel.prepareInstall(item.transportUrl)
            },
            onConfigure = { item ->
                val configureUrl = item.configureUrl
                if (configureUrl != null) {
                    showCatalogBrowser = false
                    remoteConfigurationUrl = configureUrl
                    remoteConfigurationName = item.manifest.name
                    showRemotePaste = true
                }
            }
        )
    }

    // 0. REMOTE PASTE / ADDON CONFIGURATION DIALOG
    if (showRemotePaste) {
        RemotePasteDialog(
            onDismissRequest = {
                showRemotePaste = false
                remoteConfigurationUrl = null
                remoteConfigurationName = null
            },
            onUrlReceived = { url ->
                urlInput = url
                showRemotePaste = false
                remoteConfigurationUrl = null
                remoteConfigurationName = null
                kotlinx.coroutines.MainScope().launch {
                    delay(150)
                    installButtonFocus.requestFocus()
                }
            },
            configurationUrl = remoteConfigurationUrl,
            addonName = remoteConfigurationName
        )
    }

    if (selectedAddon != null) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(
            onDismissRequest = { selectedAddon = null },
            title = "Manage Addon"
        ) {
            Text(
                selectedAddon!!.nickname ?: selectedAddon!!.name,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(24.dp))

            VoidButton(
                text = "Rename",
                onClick = {
                    renameOp = RenameOperation(selectedAddon!!.transportUrl, selectedAddon!!.nickname ?: selectedAddon!!.name)
                    selectedAddon = null
                },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = focusRequester
            )

            Spacer(Modifier.height(12.dp))

            VoidButton(
                text = "Move",
                onClick = {
                    reorderingAddon = selectedAddon
                    selectedAddon = null
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            VoidButton(
                text = "Uninstall",
                onClick = {
                    addonToDelete = selectedAddon
                    selectedAddon = null
                },
                modifier = Modifier.fillMaxWidth(),
                isDestructive = true
            )

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                VoidButton(text = "Close", onClick = { selectedAddon = null }, modifier = Modifier.width(120.dp))
            }
        }
    }

    // 2. RENAME DIALOG
    renameOp?.let { op ->
        var newName by remember { mutableStateOf(op.currentName) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(onDismissRequest = { renameOp = null }, title = "Rename") {
            VoidInput(value = newName, onValueChange = { newName = it }, placeholder = "Name")
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(text = "Cancel", onClick = { renameOp = null }, modifier = Modifier.weight(1f))
                VoidButton(text = "Save", onClick = { viewModel.renameAddon(op.transportUrl, newName); renameOp = null }, isPrimary = true, modifier = Modifier.weight(1f), focusRequester = focusRequester)
            }
        }
    }

    // 3. DELETE DIALOG
    addonToDelete?.let { addon ->
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(onDismissRequest = { addonToDelete = null }, title = "Uninstall?") {
            Text("Are you sure you want to remove ${addon.name}?", color = Color.Gray)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(text = "Cancel", onClick = { addonToDelete = null }, modifier = Modifier.weight(1f))
                VoidButton(
                    text = "Uninstall",
                    onClick = {
                        val addons = state.addons
                        val idx = addons.indexOfFirst { it.transportUrl == addon.transportUrl }
                        val neighborUrl = when {
                            idx > 0 -> addons[idx - 1].transportUrl
                            idx >= 0 && addons.size > 1 -> addons[idx + 1].transportUrl
                            else -> null
                        }
                        if (neighborUrl != null) {
                            pendingFocusUrl = neighborUrl
                        } else {
                            focusInstallAfterDelete = true
                        }
                        viewModel.deleteAddon(addon.transportUrl)
                        addonToDelete = null
                    },
                    isDestructive = true,
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester
                )
            }
        }
    }

    // 4. INSTALL CONFIG DIALOG
    if (state.pendingInstall != null) {
        val item = state.pendingInstall!!
        var home by remember { mutableStateOf(true) }
        var movies by remember { mutableStateOf(false) }
        var series by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(onDismissRequest = { viewModel.cancelInstall() }, title = "Configure Sync") {
            Text("Select which catalogs to sync:", color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp))

            if (item.debridKeyInjected) {
                Text(
                    "Your debrid key was added to this addon's URL automatically.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            VoidToggleRow("Home Screen", home, { home = !home }, focusRequester)
            Spacer(Modifier.height(8.dp))
            VoidToggleRow("Movies Tab", movies, { movies = !movies })
            Spacer(Modifier.height(8.dp))
            VoidToggleRow("Series Tab", series, { series = !series })

            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(text = "Cancel", onClick = { viewModel.cancelInstall() }, modifier = Modifier.weight(1f))
                VoidButton(text = "Install", onClick = { viewModel.confirmInstall(item.url, home, movies, series) }, isPrimary = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

// --- VOID COMPONENTS ---

@Composable
fun VoidAddonItem(
    addon: AddonEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isReordering: Boolean = false,
    focusRequester: FocusRequester? = null,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {
    val displayName = addon.nickname ?: addon.name
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val accentColor = MaterialTheme.colorScheme.primary

    val borderColor by animateColorAsState(
        if (isReordering) accentColor
        else if (isFocused) accentColor
        else Color.Transparent
    )
    val borderWidth = if (isReordering || isFocused) 2.dp else 0.dp

    val bgColor = Color.White.copy(0.05f)
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .then(
                if (isReordering) Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown) {
                        when (it.key) {
                            Key.DirectionUp -> { onMoveUp(); true }
                            Key.DirectionDown -> { onMoveDown(); true }
                            else -> false
                        }
                    } else if (it.type == KeyEventType.KeyUp) {
                        when (it.key) {
                            Key.DirectionUp, Key.DirectionDown -> true
                            else -> false
                        }
                    } else false
                } else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isFocused) Color.White else Color.White.copy(0.7f)
            )
            Text(
                text = addon.transportUrl,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isReordering) {
            Text("▲▼", color = accentColor, style = MaterialTheme.typography.labelMedium)
        } else if (isFocused) {
            Text("Manage", color = accentColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun VoidInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderBrush = if (isFocused) {
        Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary))
    } else {
        SolidColor(Color.White.copy(0.1f))
    }

    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(0.5f))
            .border(if(isFocused) 2.dp else 1.dp, borderBrush, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) Text(placeholder, color = Color.Gray)

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { onDone?.invoke() }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
        )
    }
}

@Composable
fun VoidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(if (isFocused && enabled) 1.05f else 1f)

    val activeColor = if (isDestructive) Color.Red else MaterialTheme.colorScheme.primary

    val bgColor = if (!enabled) Color.White.copy(0.05f) else Color.White.copy(0.08f)
    // Unfocused is always plain white/dimmed — isDestructive/isPrimary only color the
    // button once it's actually the one focused, so which action needs a deliberate
    // move to reach is never ambiguous.
    val textColor = when {
        !enabled -> Color.White.copy(0.3f)
        isFocused -> activeColor
        else -> Color.White
    }
    val borderColor = when {
        !enabled -> Color.White.copy(0.1f)
        isFocused -> activeColor
        else -> Color.White.copy(0.2f)
    }

    Box(
        modifier = modifier
            .height(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(interactionSource = interactionSource, indication = null) { onClick() } else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (enabled) Modifier.focusable(interactionSource = interactionSource) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}

@Composable
fun VoidToggleRow(
    label: String,
    isChecked: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgColor = if (isFocused) Color.White.copy(0.1f) else Color.Transparent
    val iconColor = if (isChecked) MaterialTheme.colorScheme.primary else Color.Gray

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null) { onToggle() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp)
    ) {
        Text(label, color = Color.White)

        // Simple Checkbox graphic
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, iconColor, RoundedCornerShape(4.dp))
                .background(if(isChecked) iconColor else Color.Transparent, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun VoidDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = modifier
                .width(rememberDialogWidth(400))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Spacer(Modifier.height(24.dp))
                content()
            }
        }
    }
}

@Composable
fun VoidIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f)
    val bgColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(0.1f)
    val iconColor = if (isFocused) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, if (isFocused) Color.Transparent else Color.White.copy(0.2f), RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
    }
}
