package com.hereliesaz.illumera.ui.addons

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import com.hereliesaz.illumera.data.model.AddonEntity
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem

internal const val STREMIO_ADDONS_URL = "https://web.stremio.com/#/addons"
internal const val THIRD_PARTY_ADDONS_URL = "https://stremio-addons.net/"

/** Prefer the exact installed transport, including configuration/token parameters. */
internal fun addonRemovalCandidates(
    item: AddonCatalogItem,
    installed: List<AddonEntity>
): List<AddonEntity> {
    val exact = installed.filter { it.transportUrl == item.transportUrl }
    return if (exact.isNotEmpty()) exact else installed.filter { it.id == item.manifest.id }
}

/** The original TV header needs 132 + 176 + 44 + two 10dp gaps. */
internal fun compactAddonHeader(availableWidthDp: Float): Boolean =
    availableWidthDp < 372f

/**
 * Full-screen, scrollable browser and installed-addon manager.
 * Source catalog selection and per-profile installations remain in AddonsViewModel.
 */
@Composable
fun AddonsScreen(
    entryRequester: FocusRequester,
    isTopNav: Boolean = false,
    viewModel: AddonsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showingInstalled by remember { mutableStateOf(false) }
    var showRemotePaste by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var removalGuidance by remember { mutableStateOf<String?>(null) }
    var restoreBrowseFocus by remember { mutableStateOf(false) }
    val focusMemory = rememberFocusMemory()
    RestoreFocusOnResume(memory = focusMemory, fallback = entryRequester)

    LaunchedEffect(showingInstalled, restoreBrowseFocus) {
        if (!showingInstalled && restoreBrowseFocus) {
            withFrameNanos { }
            runCatching { entryRequester.requestFocus() }
            restoreBrowseFocus = false
        }
    }

    // A root screen's Back opens the main navigation. Only close our own
    // Installed subview here; root Back is handled by MainRootBackHandler.
    BackHandler(enabled = showingInstalled) {
        showingInstalled = false
        restoreBrowseFocus = true
        removalGuidance = null
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .focusMemoryRoot(focusMemory)
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = if (isTopNav) 34.dp else 104.dp,
                top = if (isTopNav) 72.dp else 24.dp,
                end = 26.dp,
                bottom = 20.dp
            )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (compactAddonHeader(maxWidth.value)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VoidButton(
                            text = "Browse",
                            onClick = {
                                showingInstalled = false
                                restoreBrowseFocus = true
                                removalGuidance = null
                            },
                            isPrimary = !showingInstalled,
                            modifier = Modifier.weight(1f).focusRequester(entryRequester)
                        )
                        VoidButton(
                            text = "Installed (${state.addons.size})",
                            onClick = {
                                showingInstalled = true
                                removalGuidance = null
                            },
                            isPrimary = showingInstalled,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    AddonIconAction(
                        icon = { Icon(Icons.Default.Link, contentDescription = "Paste an addon link from phone") },
                        label = "Paste an addon link from phone",
                        onClick = { showRemotePaste = true }
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VoidButton(
                        text = "Browse",
                        onClick = {
                            showingInstalled = false
                            restoreBrowseFocus = true
                            removalGuidance = null
                        },
                        isPrimary = !showingInstalled,
                        modifier = Modifier.width(132.dp).focusRequester(entryRequester)
                    )
                    VoidButton(
                        text = "Installed (${state.addons.size})",
                        onClick = {
                            showingInstalled = true
                            removalGuidance = null
                        },
                        isPrimary = showingInstalled,
                        modifier = Modifier.width(176.dp)
                    )
                    AddonIconAction(
                        icon = { Icon(Icons.Default.Link, contentDescription = "Paste an addon link from phone") },
                        label = "Paste an addon link from phone",
                        onClick = { showRemotePaste = true }
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) {
            VoidInput(
                value = installUrl,
                onValueChange = { installUrl = it },
                placeholder = "Addon manifest URL or stremio:// link",
                onDone = { if (installUrl.isNotBlank()) viewModel.prepareInstall(installUrl) },
                modifier = Modifier.weight(1f)
            )
            AddonIconAction(
                icon = { Icon(Icons.Default.Add, contentDescription = "Install addon URL") },
                label = "Install addon URL",
                onClick = { if (installUrl.isNotBlank() && !state.isLoading) viewModel.prepareInstall(installUrl) }
            )
        }

        removalGuidance?.let { guidance ->
            Text(
                guidance,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(8.dp))
        if (showingInstalled) {
            Text("Installed Add-ons", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.addons, key = { it.transportUrl }) { addon ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(addon.nickname ?: addon.name, color = Color.White)
                            Text(
                                addon.transportUrl,
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        AddonIconAction(
                            icon = {
                                Icon(Icons.Default.Delete, contentDescription = "Uninstall ${addon.nickname ?: addon.name}")
                            },
                            label = "Uninstall ${addon.nickname ?: addon.name}",
                            onClick = { pendingRemoval = addon.transportUrl }
                        )
                    }
                }
            }
        } else {
            AddonCatalogContent(
                modifier = Modifier.weight(1f),
                state = state,
                onLoad = { viewModel.loadCatalog() },
                onSourceSelected = viewModel::selectCatalogSource,
                onAddCollection = viewModel::addCollection,
                onCollectionSelected = viewModel::selectCollection,
                onCollectionRemoved = viewModel::removeCollection,
                onRetry = viewModel::retryCatalog,
                onInstall = { viewModel.prepareInstall(it.transportUrl) },
                onRemove = { item: AddonCatalogItem ->
                    val matches = addonRemovalCandidates(item, state.addons)
                    when (matches.size) {
                        1 -> pendingRemoval = matches.single().transportUrl
                        0 -> removalGuidance = "This addon has no matching installation."
                        else -> {
                            // Multiple installed configurations share a manifest ID.
                            // Never select one arbitrarily; show their exact URLs.
                            removalGuidance = "Choose the configured installation to uninstall below."
                            showingInstalled = true
                        }
                    }
                },
                onConfigure = { item ->
                    item.configureUrl?.let { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            )
        }
    }

    if (showRemotePaste) {
        RemotePasteDialog(
            onDismissRequest = { showRemotePaste = false },
            onUrlReceived = { received ->
                installUrl = received
                viewModel.prepareInstall(received)
            }
        )
    }
    state.pendingInstall?.let { pending ->
        VoidDialog(
            title = "Install ${pending.addonName}?",
            onDismissRequest = viewModel::cancelInstall
        ) {
            Text("${pending.catalogCount} catalogs available. Add them to Illumera?", color = Color.White)
            Spacer(Modifier.height(16.dp))
            VoidButton(
                text = "Install and show catalogs",
                onClick = { viewModel.confirmInstall(pending.url, true, true, true) },
                isPrimary = true,
                modifier = Modifier.fillMaxWidth()
            )
            VoidButton(
                text = "Install without catalogs",
                onClick = { viewModel.confirmInstall(pending.url, false, false, false) },
                modifier = Modifier.fillMaxWidth()
            )
            VoidButton(
                text = "Cancel",
                onClick = viewModel::cancelInstall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    pendingRemoval?.let { url ->
        VoidDialog(title = "Uninstall addon?", onDismissRequest = { pendingRemoval = null }) {
            Text("Remove this addon from the current profile?", color = Color.White)
            Spacer(Modifier.height(16.dp))
            VoidButton(
                text = "Uninstall",
                onClick = {
                    viewModel.deleteAddon(url)
                    pendingRemoval = null
                },
                isDestructive = true,
                modifier = Modifier.fillMaxWidth()
            )
            VoidButton(
                text = "Cancel",
                onClick = { pendingRemoval = null },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
