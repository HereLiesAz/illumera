package com.hereliesaz.illumera.ui.addons

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.hereliesaz.illumera.ui.navigation.focus.RestoreFocusOnResume
import com.hereliesaz.illumera.ui.navigation.focus.focusMemoryRoot
import com.hereliesaz.illumera.ui.navigation.focus.rememberFocusMemory
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem

internal const val STREMIO_ADDONS_URL = "https://web.stremio.com/#/addons"
internal const val THIRD_PARTY_ADDONS_URL = "https://stremio-addons.net/"

/** Match only the exact installed endpoint, never a shared manifest ID. */
internal fun addonRemovalCandidates(
    item: AddonCatalogItem,
    installed: List<com.hereliesaz.illumera.data.model.AddonEntity>
): List<com.hereliesaz.illumera.data.model.AddonEntity> {
    val url = normalizeCatalogTransportUrl(item.transportUrl)
    return installed.filter { normalizeCatalogTransportUrl(it.transportUrl) == url }
}

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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showingInstalled by remember { mutableStateOf(false) }
    var showTorrentioConfig by remember { mutableStateOf(false) }
    if (showTorrentioConfig) {
        TorrentioConfigDialog(
            installedUrl = viewModel.installedTorrentioUrl(),
            connectedDebrid = remember { viewModel.connectedDebrid() },
            onSave = { config ->
                viewModel.saveTorrentio(config)
                showTorrentioConfig = false
            },
            onDismiss = { showTorrentioConfig = false }
        )
    }
    var showRemotePaste by remember { mutableStateOf(false) }
    var showInstallUrl by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var removalGuidance by remember { mutableStateOf<String?>(null) }
    var handoffToInstalled by remember { mutableStateOf(false) }
    val focusMemory = rememberFocusMemory()
    RestoreFocusOnResume(memory = focusMemory, fallback = entryRequester)
    LaunchedEffect(showingInstalled, handoffToInstalled) {
        if (showingInstalled && handoffToInstalled) {
            withFrameNanos { }
            if (!focusMemory.screenHasFocus) focusMemory.requestFocus("addons:installed")
            handoffToInstalled = false
        }
    }

    // A root screen's Back opens the main navigation. Only close our own
    // Installed subview here; root Back is handled by MainRootBackHandler.
    BackHandler(enabled = showingInstalled) { showingInstalled = false; removalGuidance = null }

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
        AddonCatalogContent(
            modifier = Modifier.weight(1f),
            state = state,
            onLoad = { viewModel.loadCatalog() },
            onSourceSelected = { source ->
                showingInstalled = false
                removalGuidance = null
                viewModel.selectCatalogSource(source)
            },
            onCollectionSelected = { collection ->
                showingInstalled = false
                viewModel.selectCollection(collection)
            },
            onRetry = viewModel::retryCatalog,
            onInstall = { viewModel.prepareInstall(it.transportUrl) },
            onRemove = { item: AddonCatalogItem ->
                val matches = addonRemovalCandidates(item, state.addons)
                when (matches.size) {
                    1 -> pendingRemoval = matches.single().transportUrl
                    0 -> removalGuidance = "No installed addon matches this exact configuration."
                    else -> {
                        removalGuidance = "Choose the configured installation below."
                        showingInstalled = true
                        // Installed is a persistent header child; transfer focus before
                        // removing the selected catalog action, not on a later frame.
                        handoffToInstalled = !focusMemory.requestFocus("addons:installed")
                    }
                }
            },
            onConfigure = { item ->
                // Torrentio is configured natively; every other addon uses its own web page.
                if (com.hereliesaz.illumera.data.debrid.TorrentioConfig.isTorrentio(item.transportUrl)) {
                    showTorrentioConfig = true
                } else (item.discoveryUrl ?: item.configureUrl)?.let { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            },
            onConfigureInstalled = { showTorrentioConfig = true },
            showingInstalled = showingInstalled,
            onInstalledSelected = { showingInstalled = true; removalGuidance = null },
            onRemoveInstalled = { pendingRemoval = it },
            onToggleInstalled = { url, enabled -> viewModel.setAddonEnabled(url, enabled) },
            onInstallUrl = { showInstallUrl = true },
            entryRequester = entryRequester,
            focusMemory = focusMemory
        )
        removalGuidance?.let { guidance ->
            Text(guidance, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (showInstallUrl) {
        VoidDialog(
            title = "Install addon URL",
            onDismissRequest = { showInstallUrl = false }
        ) {
            VoidInput(
                value = installUrl,
                onValueChange = { installUrl = it },
                placeholder = "Addon manifest URL or stremio:// link",
                onDone = {
                    if (installUrl.isNotBlank()) {
                        viewModel.prepareInstall(installUrl)
                        showInstallUrl = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true
            )
            Spacer(Modifier.height(10.dp))
            val clipboardContext = LocalContext.current
            VoidButton(
                // This device's own clipboard: replaces the field with the first clip's text.
                text = "Paste from clipboard",
                onClick = {
                    val clipboard = clipboardContext.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(clipboardContext)?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { installUrl = it }
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true
            )
            VoidButton(
                text = "Paste link from phone",
                onClick = {
                    showInstallUrl = false
                    showRemotePaste = true
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true
            )
            VoidButton(
                text = "Install",
                onClick = {
                    if (installUrl.isNotBlank()) {
                        viewModel.prepareInstall(installUrl)
                        showInstallUrl = false
                    }
                },
                enabled = installUrl.isNotBlank() && !state.isLoading,
                isPrimary = true,
                modifier = Modifier.fillMaxWidth(),
                compact = true
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
            Text("${pending.catalogCount} catalogs available. Add them to Illumera?", color = MaterialTheme.colorScheme.onBackground)
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
            Text("Remove this addon from the current profile?", color = MaterialTheme.colorScheme.onBackground)
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
