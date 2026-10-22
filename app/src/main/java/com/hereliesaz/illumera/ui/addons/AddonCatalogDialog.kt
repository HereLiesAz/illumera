package com.hereliesaz.illumera.ui.addons

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogSource
import com.hereliesaz.illumera.data.model.stremio.SavedAddonCollection
import com.hereliesaz.illumera.ui.navigation.focus.FocusMemory
import com.hereliesaz.illumera.ui.navigation.focus.restorableFocus

@Composable
fun AddonCatalogContent(
    state: AddonsViewModel.UiState,
    onLoad: () -> Unit,
    onSourceSelected: (AddonCatalogSource) -> Unit,
    onAddCollection: (String) -> Unit,
    onCollectionSelected: (SavedAddonCollection) -> Unit,
    onCollectionRemoved: (SavedAddonCollection) -> Unit,
    onRetry: () -> Unit,
    onInstall: (AddonCatalogItem) -> Unit,
    onRemove: (AddonCatalogItem) -> Unit,
    onConfigure: (AddonCatalogItem) -> Unit,
    showingInstalled: Boolean = false,
    onInstalledSelected: () -> Unit = {},
    onRemoveInstalled: (String) -> Unit = {},
    onToggleInstalled: (String, Boolean) -> Unit = { _, _ -> },
    onConfigureInstalled: (String) -> Unit = {},
    onInstallUrl: () -> Unit = {},
    modifier: Modifier = Modifier,
    entryRequester: FocusRequester? = null,
    focusMemory: FocusMemory
) {
    var query by remember { mutableStateOf("") }
    var addingCollection by remember { mutableStateOf(false) }
    var collectionUrl by remember { mutableStateOf("") }
    val initialFocusRequester = remember { FocusRequester() }

    // A full-screen catalog must not steal focus from the Browse/Installed
    // header (or the user-selected catalog item) on every remount.
    LaunchedEffect(Unit) { onLoad() }

    val filteredInstalled = remember(state.addons, query) {
        val needle = query.trim()
        state.addons.filter { addon ->
            needle.isEmpty() || addon.name.contains(needle, true) ||
                addon.nickname.orEmpty().contains(needle, true) ||
                addon.transportUrl.contains(needle, true)
        }
    }
    val filteredItems = remember(state.catalogItems, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            state.catalogItems
        } else {
            state.catalogItems.filter { item ->
                val manifest = item.manifest
                manifest.name.lowercase().contains(needle) ||
                    manifest.description.orEmpty().lowercase().contains(needle) ||
                    manifest.types.orEmpty().any { it.lowercase().contains(needle) } ||
                    manifest.id.lowercase().contains(needle)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
            Text(
                "Browse Add-ons",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Official, Community, 3rd Party, and saved collections",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(18.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "all") {
                    VoidButton(
                        text = "All",
                        onClick = { onSourceSelected(AddonCatalogSource.ALL) },
                        isPrimary = !showingInstalled && state.catalogSource == AddonCatalogSource.ALL,
                        modifier = Modifier.width(66.dp).focusRequester(entryRequester ?: initialFocusRequester).restorableFocus("addons:all", focusMemory),
                        compact = true
                    )
                }
                item(key = "installed") {
                    VoidButton(
                        text = "Installed (${state.addons.size})",
                        onClick = onInstalledSelected,
                        isPrimary = showingInstalled,
                        modifier = Modifier.width(120.dp).restorableFocus("addons:installed", focusMemory),
                        compact = true
                    )
                }
                listOf(AddonCatalogSource.OFFICIAL, AddonCatalogSource.COMMUNITY, AddonCatalogSource.STREMIO_ADDONS_NET).forEach { source ->
                    item(key = source.name) {
                        VoidButton(
                            text = source.displayName,
                            onClick = { onSourceSelected(source) },
                            isPrimary = !showingInstalled && state.catalogSource == source,
                            modifier = Modifier.width(if (source == AddonCatalogSource.STREMIO_ADDONS_NET) 98.dp else 90.dp).restorableFocus("addons:source:${source.name}", focusMemory),
                            compact = true
                        )
                    }
                }
                item(key = "add_collection") {
                    VoidButton(
                        text = "+ Collection",
                        onClick = { addingCollection = !addingCollection },
                        isPrimary = addingCollection,
                        modifier = Modifier.width(110.dp).restorableFocus("addons:collection-add", focusMemory),
                        compact = true
                    )
                }
                item(key = "install_url") {
                    VoidButton(
                        text = "Install URL",
                        onClick = onInstallUrl,
                        modifier = Modifier.width(106.dp).restorableFocus("addons:install-url", focusMemory),
                        compact = true
                    )
                }
            }

            if (!showingInstalled && state.catalogSource == AddonCatalogSource.STREMIO_ADDONS_NET) {
                Text(
                    "Addons sourced from stremio-addons.net — https://stremio-addons.net/",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            if (addingCollection) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VoidInput(
                        value = collectionUrl,
                        onValueChange = { collectionUrl = it },
                        placeholder = "Addon collection URL",
                        modifier = Modifier.weight(1f)
                    )
                    VoidButton(
                        text = "Add",
                        onClick = {
                            if (collectionUrl.isNotBlank()) {
                                onAddCollection(collectionUrl.trim())
                                collectionUrl = ""
                                addingCollection = false
                            }
                        },
                        enabled = collectionUrl.isNotBlank() && !state.isCatalogLoading,
                        isPrimary = collectionUrl.isNotBlank(),
                        modifier = Modifier.width(110.dp)
                    )
                }
                Text(
                    "Supports Stremio addon-catalog manifests, modern collection JSON, and compatible legacy repositories.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (!showingInstalled && state.savedCollections.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Collections",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.savedCollections, key = { it.url }) { collection ->
                        VoidButton(
                            text = collection.name.take(22),
                            onClick = { onCollectionSelected(collection) },
                            isPrimary = state.catalogSource == AddonCatalogSource.COLLECTION &&
                                state.activeCollection?.url == collection.url,
                            modifier = Modifier.width(190.dp).restorableFocus("addons:collection:${collection.url}", focusMemory)
                        )
                    }
                }

                if (state.catalogSource == AddonCatalogSource.COLLECTION && state.activeCollection != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        VoidButton(
                            text = "Forget collection",
                            onClick = { onCollectionRemoved(state.activeCollection) },
                            modifier = Modifier.width(180.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            VoidInput(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search addons",
                modifier = Modifier.fillMaxWidth().restorableFocus("addons:search", focusMemory),
                compact = true
            )

            Spacer(Modifier.height(10.dp))

            if (showingInstalled) {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredInstalled, key = { it.transportUrl }) { addon ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    addon.nickname ?: addon.name,
                                    color = MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = if (addon.isEnabled) 1f else 0.5f)
                                )
                                Text(
                                    if (addon.isEnabled) addon.transportUrl else "Disabled · ${addon.transportUrl}",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            val addonLabel = addon.nickname ?: addon.name
                            if (com.hereliesaz.illumera.data.debrid.TorrentioConfig.isTorrentio(addon.transportUrl)) {
                                AddonIconAction(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = "Configure $addonLabel",
                                    onClick = { onConfigureInstalled(addon.transportUrl) },
                                    modifier = Modifier.restorableFocus("addons:configure:${addon.transportUrl}", focusMemory)
                                )
                            }
                            AddonIconAction(
                                icon = {
                                    Icon(
                                        if (addon.isEnabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                                        contentDescription = null
                                    )
                                },
                                label = if (addon.isEnabled) "Disable $addonLabel" else "Enable $addonLabel",
                                onClick = { onToggleInstalled(addon.transportUrl, !addon.isEnabled) },
                                modifier = Modifier.restorableFocus("addons:toggle:${addon.transportUrl}", focusMemory)
                            )
                            AddonIconAction(
                                icon = { Icon(Icons.Default.Delete, contentDescription = "Uninstall ${addon.nickname ?: addon.name}") },
                                label = "Uninstall ${addon.nickname ?: addon.name}",
                                onClick = { onRemoveInstalled(addon.transportUrl) },
                                modifier = Modifier.restorableFocus("addons:installed:${addon.transportUrl}", focusMemory)
                            )
                        }
                    }
                }
            } else when {
                state.isCatalogLoading && state.catalogItems.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text("Loading ${catalogSourceName(state).lowercase()} addons…", color = Color.Gray)
                        }
                    }
                }

                state.catalogError != null && state.catalogItems.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            state.catalogError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        VoidButton(
                            text = "Retry",
                            onClick = onRetry,
                            enabled = state.catalogSource != AddonCatalogSource.COLLECTION || state.activeCollection != null,
                            modifier = Modifier.width(150.dp)
                        )
                    }
                }

                else -> {
                    if (state.isCatalogLoading) {
                        Text(
                            "Refreshing…",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    if (filteredItems.isEmpty()) {
                        Text(
                            if (query.isBlank()) "No addons were returned by this catalog." else "No addons match your search.",
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 28.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredItems, key = { "${state.catalogSource}:${it.transportUrl}:${it.manifest.id}" }) { item ->
                                AddonCatalogRow(
                                    item = item,
                                    focusMemory = focusMemory,
                                    installed = state.addons.any { installed ->
                                        normalizeCatalogTransportUrl(installed.transportUrl) == normalizeCatalogTransportUrl(item.transportUrl)
                                    },
                                    onInstall = { onInstall(item) },
                                    onRemove = { onRemove(item) },
                                    onConfigure = { onConfigure(item) }
                                )
                            }
                        }
                    }
                }
            }

    }
}

@Composable
private fun AddonCatalogRow(
    item: AddonCatalogItem,
    focusMemory: FocusMemory,
    installed: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onConfigure: () -> Unit
) {
    val manifest = item.manifest
    val localTransport = isLocalTransport(item.transportUrl)
    val modernTransport = item.isModernManifestTransport
    val installable = modernTransport && !localTransport
    val configurationRequired = manifest.behaviorHints?.configurationRequired == true
    val configurable = item.configureUrl != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            if (!manifest.logo.isNullOrBlank()) {
                AsyncImage(
                    model = manifest.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(50.dp)
                )
            } else {
                Text(
                    manifest.name.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                manifest.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!manifest.description.isNullOrBlank()) {
                Text(
                    manifest.description,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 7.dp)
            ) {
                if (item.flags?.official == true) AddonBadge("Official")
                manifest.types.orEmpty().take(3).forEach { AddonBadge(it) }
                if (manifest.behaviorHints?.p2p == true) AddonBadge("P2P")
                if (manifest.behaviorHints?.adult == true) AddonBadge("Adult")
                if (configurable) AddonBadge("Configurable")
                if (localTransport) AddonBadge("Local service")
                else if (!modernTransport) AddonBadge("Legacy transport")
            }
        }

        Spacer(Modifier.width(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (configurable && installable) {
                AddonIconAction(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Configure ${manifest.name}") },
                    label = "Configure ${manifest.name}",
                    onClick = onConfigure,
                    modifier = Modifier.restorableFocus("addons:configure:${item.transportUrl}", focusMemory)
                )
            }
            if (installed) {
                AddonIconAction(
                    icon = { Icon(Icons.Default.Delete, contentDescription = "Uninstall ${manifest.name}") },
                    label = "Uninstall ${manifest.name}",
                    onClick = onRemove,
                    modifier = Modifier.restorableFocus("addons:remove:${item.transportUrl}", focusMemory)
                )
            } else if (!configurationRequired && installable) {
                AddonIconAction(
                    icon = { Icon(Icons.Default.Add, contentDescription = "Install ${manifest.name}") },
                    label = "Install ${manifest.name}",
                    onClick = onInstall,
                    modifier = Modifier.restorableFocus("addons:install:${item.transportUrl}", focusMemory)
                )
            } else if (!installable) {
                Text("Unavailable", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Icon-only addon action with an explicit TV-focus outline and accessible label. */
@Composable
internal fun AddonIconAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    IconButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.size(44.dp)
            .semantics { contentDescription = label }
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                RoundedCornerShape(8.dp)
            )
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun AddonBadge(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

private fun catalogSourceName(state: AddonsViewModel.UiState): String = when (state.catalogSource) {
    AddonCatalogSource.ALL -> AddonCatalogSource.ALL.displayName
    AddonCatalogSource.STREMIO_ADDONS_NET -> AddonCatalogSource.STREMIO_ADDONS_NET.displayName
    AddonCatalogSource.OFFICIAL -> AddonCatalogSource.OFFICIAL.displayName
    AddonCatalogSource.COMMUNITY -> AddonCatalogSource.COMMUNITY.displayName
    AddonCatalogSource.COLLECTION -> state.activeCollection?.name ?: AddonCatalogSource.COLLECTION.displayName
}

/** Keep configured paths and query parameters distinct; only strip the manifest suffix. */
internal fun normalizeCatalogTransportUrl(url: String): String {
    val trimmed = url.trim()
    val base = trimmed.substringBefore('?')
    val query = trimmed.substringAfter('?', "").takeIf { '?' in trimmed }
    val withoutManifest = base.removeSuffix("/manifest.json").trimEnd('/')
    return withoutManifest + (query?.let { "?$it" } ?: "")
}

private fun isLocalTransport(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https" && scheme != "stremio") return true
    val host = uri.host?.lowercase().orEmpty()
    return host.isBlank() || host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0"
}
