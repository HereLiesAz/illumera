package com.hereliesaz.illumera.ui.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.data.debrid.TorrentioConfig
import com.hereliesaz.illumera.data.model.debrid.DebridProvider

/**
 * Native Torrentio configuration, replacing its web configure page. Starts from the
 * installed URL's settings; when no debrid key is set yet, the primary connected debrid service
 * (Settings → Integrations) and its key are filled in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorrentioConfigDialog(
    installedUrl: String?,
    /** Connected services and keys, primary first. */
    connectedDebrid: List<Pair<DebridProvider, String>>,
    onSave: (TorrentioConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var config by remember(installedUrl) {
        val parsed = installedUrl?.let(TorrentioConfig::parse) ?: TorrentioConfig()
        mutableStateOf(
            val primary = connectedDebrid.firstOrNull()
            if (parsed.debridProvider == null && primary != null) {
                parsed.copy(debridProvider = primary.first, debridKey = primary.second)
            } else parsed
        )
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    VoidDialog(onDismissRequest = onDismiss, title = "Configure Torrentio") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoidButton("Save", onClick = { onSave(config) }, isPrimary = true, compact = true, focusRequester = firstFocus)
            VoidButton("Cancel", onClick = onDismiss, compact = true)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
        ) {
            Section("Debrid service")
            Chips {
                Chip("None", config.debridProvider == null) {
                    config = config.copy(debridProvider = null, debridKey = "")
                }
                DebridProvider.entries.forEach { provider ->
                    Chip(provider.displayName, config.debridProvider == provider) {
                        val key = connectedDebrid.firstOrNull { it.first == provider }?.second
                            ?: config.debridKey.takeIf { config.debridProvider == provider }.orEmpty()
                        config = config.copy(debridProvider = provider, debridKey = key)
                    }
                }
            }
            if (config.debridProvider != null) {
                Spacer(Modifier.height(8.dp))
                VoidInput(
                    value = config.debridKey,
                    onValueChange = { config = config.copy(debridKey = it.trim()) },
                    placeholder = "${config.debridProvider?.displayName} API key",
                    modifier = Modifier.fillMaxWidth(),
                    compact = true
                )
                connectedDebrid
                    .firstOrNull { it.first == config.debridProvider && it.second == config.debridKey }
                    ?.let { Note("Filled in from your connected ${it.first.displayName} account.") }
                VoidToggleRow("Hide download-to-debrid links", config.noDownloadLinks, {
                    config = config.copy(noDownloadLinks = !config.noDownloadLinks)
                })
                VoidToggleRow("Hide debrid cloud catalog", config.noDebridCatalog, {
                    config = config.copy(noDebridCatalog = !config.noDebridCatalog)
                })
            }

            Section("Sorting")
            Chips {
                TorrentioConfig.SORT_OPTIONS.forEach { (id, label) ->
                    Chip(label, config.sort == id) { config = config.copy(sort = id) }
                }
            }

            Section("Results per quality")
            Chips {
                TorrentioConfig.LIMITS.forEach { limit ->
                    Chip(limit?.toString() ?: "All", config.limit == limit) { config = config.copy(limit = limit) }
                }
            }

            Section("Exclude qualities")
            Chips {
                TorrentioConfig.QUALITY_FILTERS.forEach { (id, label) ->
                    Chip(label, id in config.excludedQualities) {
                        config = config.copy(excludedQualities = config.excludedQualities.toggle(id))
                    }
                }
            }

            Section("Providers")
            Note("None selected uses Torrentio's defaults.")
            Chips {
                TorrentioConfig.PROVIDERS.forEach { (id, label) ->
                    Chip(label, id in config.providers) {
                        config = config.copy(providers = config.providers.toggle(id))
                    }
                }
            }

            Section("Priority languages")
            Chips {
                TorrentioConfig.LANGUAGES.forEach { language ->
                    Chip(language.replaceFirstChar { it.uppercase() }, language in config.languages) {
                        config = config.copy(
                            languages = if (language in config.languages) config.languages - language
                            else config.languages + language
                        )
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    VoidButton(text = label, onClick = onClick, isPrimary = selected, compact = true)
}
