package com.hereliesaz.illumera.ui.addons

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogSource
import com.hereliesaz.illumera.data.model.stremio.SavedAddonCollection
import com.hereliesaz.illumera.ui.util.rememberDialogWidth

@Composable
fun AddonCatalogDialog(
    state: AddonsViewModel.UiState,
    onDismissRequest: () -> Unit,
    onLoad: () -> Unit,
    onSourceSelected: (AddonCatalogSource) -> Unit,
    onAddCollection: (String) -> Unit,
    onCollectionSelected: (SavedAddonCollection) -> Unit,
    onCollectionRemoved: (SavedAddonCollection) -> Unit,
    onRetry: () -> Unit,
    onInstall: (AddonCatalogItem) -> Unit,
    onConfigure: (AddonCatalogItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var addingCollection by remember { mutableStateOf(false) }
    var collectionUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onLoad()
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

    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .width(rememberDialogWidth(760))
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text(
                "Browse Stremio Addons",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Official, Community, and addon collections — installed through Illumera.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.58f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(AddonCatalogSource.OFFICIAL, AddonCatalogSource.COMMUNITY).forEach { source ->
                    VoidButton(
                        text = source.displayName,
                        onClick = { onSourceSelected(source) },
                        isPrimary = state.catalogSource == source,
                        modifier = Modifier.width(150.dp)
                    )
                }
                VoidButton(
                    text = "+ Collection",
                    onClick = { addingCollection = !addingCollection },
                    isPrimary = addingCollection,
                    modifier = Modifier.width(170.dp)
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
                    color = Color.White.copy(alpha = 0.48f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (state.savedCollections.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Collections",
                    color = Color.White.copy(alpha = 0.62f),
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
                            modifier = Modifier.width(190.dp)
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

            Spacer(Modifier.height(14.dp))

            VoidInput(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search addons",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            when {
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
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredItems, key = { "${state.catalogSource}:${it.transportUrl}:${it.manifest.id}" }) { item ->
                                AddonCatalogRow(
                                    item = item,
                                    installed = state.addons.any { installed ->
                                        installed.id == item.manifest.id ||
                                            normalizeTransportUrl(installed.transportUrl) == normalizeTransportUrl(item.transportUrl)
                                    },
                                    onInstall = { onInstall(item) },
                                    onConfigure = { onConfigure(item) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                VoidButton(
                    text = "Close",
                    onClick = onDismissRequest,
                    modifier = Modifier.width(130.dp)
                )
            }
        }
    }
}

@Composable
private fun AddonCatalogRow(
    item: AddonCatalogItem,
    installed: Boolean,
    onInstall: () -> Unit,
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
            .background(Color.White.copy(alpha = 0.05f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f)),
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
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!manifest.description.isNullOrBlank()) {
                Text(
                    manifest.description,
                    color = Color.White.copy(alpha = 0.58f),
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (configurable && installable) {
                VoidButton(
                    text = "Configure",
                    onClick = onConfigure,
                    modifier = Modifier.width(130.dp)
                )
            }

            if (!configurationRequired && installable) {
                VoidButton(
                    text = if (installed) "Installed" else "Install",
                    onClick = onInstall,
                    enabled = !installed,
                    isPrimary = !installed,
                    modifier = Modifier.width(120.dp)
                )
            } else if (!installable) {
                VoidButton(
                    text = "Unavailable",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.width(130.dp)
                )
            }
        }
    }
}

@Composable
private fun AddonBadge(label: String) {
    Text(
        label,
        color = Color.White.copy(alpha = 0.68f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

private fun catalogSourceName(state: AddonsViewModel.UiState): String = when (state.catalogSource) {
    AddonCatalogSource.OFFICIAL -> AddonCatalogSource.OFFICIAL.displayName
    AddonCatalogSource.COMMUNITY -> AddonCatalogSource.COMMUNITY.displayName
    AddonCatalogSource.COLLECTION -> state.activeCollection?.name ?: AddonCatalogSource.COLLECTION.displayName
}

private fun normalizeTransportUrl(url: String): String =
    url.removeSuffix("/manifest.json").trimEnd('/').lowercase()

private fun isLocalTransport(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https" && scheme != "stremio") return true
    val host = uri.host?.lowercase().orEmpty()
    return host.isBlank() || host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0"
}
