package com.hereliesaz.illumera.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogSource
import com.hereliesaz.illumera.data.model.stremio.SavedAddonCollection
import com.hereliesaz.illumera.data.repository.AddonCatalogRepository
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.addons.VoidDialog
import com.hereliesaz.illumera.ui.addons.VoidInput
import com.hereliesaz.illumera.ui.addons.VoidToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Addon repositories, managed from Integrations: the built-in catalogs (Official, Community,
 * 3rd Party) can be switched off, and collection URLs added, ordered and removed. The Add-ons
 * browser shows only what is switched on here, and follows changes immediately.
 */
@HiltViewModel
class AddonRepositoriesViewModel @Inject constructor(
    private val catalogRepository: AddonCatalogRepository
) : ViewModel() {
    val repositories: StateFlow<AddonCatalogRepository.Repositories> = catalogRepository.repositories

    data class AddState(val isAdding: Boolean = false, val error: String? = null)

    private val _addState = MutableStateFlow(AddState())
    val addState: StateFlow<AddState> = _addState.asStateFlow()

    fun setBuiltInEnabled(source: AddonCatalogSource, enabled: Boolean) =
        catalogRepository.setBuiltInEnabled(source, enabled)

    fun move(collection: SavedAddonCollection, delta: Int) =
        catalogRepository.moveCollection(collection.url, delta)

    fun remove(collection: SavedAddonCollection) = catalogRepository.removeCollection(collection.url)

    /** Fetches [url] first, so only a working repository is saved. */
    fun add(url: String, onAdded: () -> Unit) {
        if (url.isBlank() || _addState.value.isAdding) return
        _addState.value = AddState(isAdding = true)
        viewModelScope.launch {
            _addState.value = try {
                catalogRepository.addCollection(url.trim())
                onAdded()
                AddState()
            } catch (_: TimeoutCancellationException) {
                AddState(error = "The repository took too long to answer")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AddState(error = e.message ?: "Could not add this repository")
            }
        }
    }
}

/** One-line summary for the Integrations entry. */
internal fun addonRepositoriesSummary(repos: AddonCatalogRepository.Repositories): String {
    val names = repos.enabledBuiltIns.map { it.displayName } + repos.collections.map { it.name }
    return if (names.isEmpty()) "None switched on" else names.joinToString(" · ")
}

@Composable
internal fun AddonRepositoriesDialog(
    viewModel: AddonRepositoriesViewModel,
    onDismiss: () -> Unit
) {
    val repos by viewModel.repositories.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    val context = LocalContext.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    VoidDialog(onDismissRequest = onDismiss, title = "Addon Repositories") {
        Column(
            modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "The Add-ons browser shows addons from the repositories switched on here.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
            AddonCatalogRepository.BUILT_IN_SOURCES.forEach { source ->
                val enabled = source in repos.enabledBuiltIns
                VoidToggleRow(
                    label = builtInLabel(source),
                    isChecked = enabled,
                    onToggle = { viewModel.setBuiltInEnabled(source, !enabled) }
                )
            }

            if (repos.collections.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Collections", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
            }
            repos.collections.forEachIndexed { index, collection ->
                Text(
                    collection.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    collection.url,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VoidButton(
                        text = "Up",
                        onClick = { viewModel.move(collection, -1) },
                        enabled = index > 0,
                        compact = true,
                        modifier = Modifier.width(70.dp)
                    )
                    VoidButton(
                        text = "Down",
                        onClick = { viewModel.move(collection, +1) },
                        enabled = index < repos.collections.lastIndex,
                        compact = true,
                        modifier = Modifier.width(80.dp)
                    )
                    VoidButton(
                        text = "Remove",
                        onClick = { viewModel.remove(collection) },
                        isDestructive = true,
                        compact = true,
                        modifier = Modifier.width(100.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Add a repository", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
            VoidInput(
                value = url,
                onValueChange = { url = it },
                placeholder = "Collection or repository URL",
                onDone = { viewModel.add(url) { url = "" } },
                modifier = Modifier.fillMaxWidth(),
                compact = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoidButton(
                    text = "Paste from clipboard",
                    onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { url = it }
                    },
                    compact = true,
                    modifier = Modifier.weight(1f)
                )
                VoidButton(
                    text = if (addState.isAdding) "Adding…" else "Add",
                    onClick = { viewModel.add(url) { url = "" } },
                    enabled = url.isNotBlank() && !addState.isAdding,
                    isPrimary = true,
                    compact = true,
                    modifier = Modifier.width(110.dp)
                )
            }
            addState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            VoidButton(text = "Done", onClick = onDismiss, compact = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun builtInLabel(source: AddonCatalogSource): String = when (source) {
    AddonCatalogSource.OFFICIAL -> "Official (Stremio)"
    AddonCatalogSource.COMMUNITY -> "Community (Stremio)"
    AddonCatalogSource.STREMIO_ADDONS_NET -> "3rd Party (stremio-addons.net)"
    else -> source.displayName
}
