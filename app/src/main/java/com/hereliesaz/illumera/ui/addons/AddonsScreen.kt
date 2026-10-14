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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

internal const val STREMIO_ADDONS_URL = "https://web.stremio.com/#/addons"
internal const val THIRD_PARTY_ADDONS_URL = "https://stremio-addons.net/"

/**
 * Illumera's native addon manager. Browsing and installation use local profile
 * state, independently of a connected Stremio account. The external directory
 * is linked with attribution; its API prohibits competing directory mirrors.
 */
@Composable
fun AddonsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") isTopNav: Boolean = false,
    viewModel: AddonsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showCatalog by remember { mutableStateOf(false) }
    var showRemotePaste by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Text("Illumera Addons", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            "Browse, install and manage addons in Illumera. No Stremio account required.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoidButton(
                text = "Browse addons",
                onClick = { showCatalog = true },
                isPrimary = true,
                modifier = Modifier.width(190.dp)
            )
            VoidButton(
                text = "Stremio Addons directory",
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(THIRD_PARTY_ADDONS_URL)))
                },
                modifier = Modifier.width(260.dp)
            )
            VoidButton(
                text = "Paste from phone",
                onClick = { showRemotePaste = true },
                modifier = Modifier.width(190.dp)
            )
        }
        Text(
            "Third-party directory: stremio-addons.net. Find an addon there, then paste its manifest or install link below.",
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 10.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoidInput(
                value = installUrl,
                onValueChange = { installUrl = it },
                placeholder = "Addon manifest URL or stremio:// link",
                modifier = Modifier.weight(1f)
            )
            VoidButton(
                text = if (state.isLoading) "Loading…" else "Install",
                onClick = { viewModel.prepareInstall(installUrl) },
                enabled = installUrl.isNotBlank() && !state.isLoading,
                modifier = Modifier.width(130.dp)
            )
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Installed addons", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.addons, key = { it.transportUrl }) { addon ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(addon.nickname ?: addon.name, color = Color.White)
                        Text(addon.transportUrl, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    VoidButton(
                        text = "Remove",
                        onClick = { pendingRemoval = addon.transportUrl },
                        isDestructive = true,
                        modifier = Modifier.width(130.dp)
                    )
                }
            }
        }
    }

    if (showCatalog) {
        AddonCatalogDialog(
            state = state,
            onDismissRequest = { showCatalog = false },
            onLoad = { viewModel.loadCatalog() },
            onSourceSelected = viewModel::selectCatalogSource,
            onAddCollection = viewModel::addCollection,
            onCollectionSelected = viewModel::selectCollection,
            onCollectionRemoved = viewModel::removeCollection,
            onRetry = viewModel::retryCatalog,
            onInstall = { viewModel.prepareInstall(it.transportUrl) },
            onConfigure = { item ->
                val url = item.configureUrl
                if (url != null) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        )
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
            Text(
                "${pending.catalogCount} catalogs available. Add them to Illumera?",
                color = Color.White
            )
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
        VoidDialog(
            title = "Remove addon?",
            onDismissRequest = { pendingRemoval = null }
        ) {
            Text("Remove this addon from the current profile?", color = Color.White)
            Spacer(Modifier.height(16.dp))
            VoidButton(
                text = "Remove",
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
