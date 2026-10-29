package com.hereliesaz.illumera.ui.addons

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.debrid.DebridAddonUrlHelper
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogItem
import com.hereliesaz.illumera.data.model.stremio.AddonCatalogSource
import com.hereliesaz.illumera.data.model.stremio.SavedAddonCollection
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonCatalogRepository
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Collections
import com.hereliesaz.illumera.data.profile.BakedAddons
import javax.inject.Inject

sealed class AddonEvent {
    data object InstallationSuccess : AddonEvent()
    data class InstallationFailure(val message: String) : AddonEvent()
}

data class AddonInstallConfig(
    val url: String,
    val addonName: String,
    val catalogCount: Int,
    val debridKeyInjected: Boolean = false
)

@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val repository: AddonRepository,
    private val catalogRepository: AddonCatalogRepository,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val debridManager: DebridManager
) : ViewModel() {

    val connectedDebridProvider: StateFlow<DebridProvider?> = debridManager.connectedProvider

    /** Only for copying to the clipboard when the user asks — never stored in UI state. */
    fun getDebridApiKeyForClipboard(): String? = debridManager.getApiKey()

    private suspend fun persistProfileState() {
        profileConfigurationManager.saveActiveRuntimeState()
        profileConfigurationManager.resetStartupCapture()
    }

    data class UiState(
        val addons: List<AddonEntity> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val pendingInstall: AddonInstallConfig? = null,
        val catalogSource: AddonCatalogSource = AddonCatalogSource.ALL,
        val activeCollection: SavedAddonCollection? = null,
        val savedCollections: List<SavedAddonCollection> = emptyList(),
        // Only enabled custom repositories and built-in sources are browsable.
        val enabledBuiltIns: List<AddonCatalogSource> = AddonCatalogRepository.BUILT_IN_SOURCES,
        val catalogItems: List<AddonCatalogItem> = emptyList(),
        val isCatalogLoading: Boolean = false,
        val catalogError: String? = null
    )

    private val _uiState = MutableStateFlow(
        UiState(savedCollections = catalogRepository.repositories.value.enabledCollections)
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<AddonEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var catalogLoadJob: Job? = null

    init {
        loadAddons()
        // Repositories are managed in Integrations; follow every change while browsing.
        viewModelScope.launch {
            catalogRepository.repositories.collect { repos ->
                val current = _uiState.value
                val sourceGone = (current.catalogSource in AddonCatalogRepository.BUILT_IN_SOURCES &&
                    current.catalogSource !in repos.enabledBuiltIns) ||
                    (current.catalogSource == AddonCatalogSource.COLLECTION &&
                        repos.enabledCollections.none { it.url == current.activeCollection?.url })
                val listChanged = current.enabledBuiltIns != repos.enabledBuiltIns ||
                    current.savedCollections != repos.enabledCollections
                _uiState.value = current.copy(
                    savedCollections = repos.enabledCollections,
                    enabledBuiltIns = repos.enabledBuiltIns,
                    activeCollection = if (sourceGone) null else current.activeCollection
                )
                // "All" spans every repository, so it reloads when the set changes.
                if (sourceGone) loadCatalog(AddonCatalogSource.ALL, force = true)
                else if (listChanged && current.catalogSource == AddonCatalogSource.ALL) {
                    loadCatalog(AddonCatalogSource.ALL, force = true)
                }
            }
        }
    }

    private fun loadAddons() {
        viewModelScope.launch {
            repository.getAddons().collect { addonList ->
                _uiState.value = _uiState.value.copy(addons = addonList)
            }
        }
    }

    fun loadCatalog(
        source: AddonCatalogSource = _uiState.value.catalogSource,
        force: Boolean = false
    ) {
        val current = _uiState.value
        val collection = if (source == AddonCatalogSource.COLLECTION) current.activeCollection else null
        if (source == AddonCatalogSource.COLLECTION && collection == null) return
        val sameTarget = current.catalogSource == source &&
            (source != AddonCatalogSource.COLLECTION || current.activeCollection == collection)
        if (!force && sameTarget && !current.isCatalogLoading && current.catalogItems.isNotEmpty()) return
        if (!force && sameTarget && current.isCatalogLoading) return

        // A source/collection change supersedes the in-flight request immediately.
        catalogLoadJob?.cancel()
        _uiState.value = current.copy(
            catalogSource = source,
            activeCollection = if (source == AddonCatalogSource.COLLECTION) collection else null,
            catalogItems = if (sameTarget) current.catalogItems else emptyList(),
            isCatalogLoading = true,
            catalogError = null
        )

        catalogLoadJob = viewModelScope.launch {
            try {
                val items = catalogRepository.fetch(source, collection)
                if (_uiState.value.catalogSource == source &&
                    (source != AddonCatalogSource.COLLECTION || _uiState.value.activeCollection == collection)
                ) {
                    _uiState.value = _uiState.value.copy(
                        catalogItems = items,
                        isCatalogLoading = false,
                        catalogError = null
                    )
                }
            } catch (_: TimeoutCancellationException) {
                if (_uiState.value.catalogSource == source &&
                    (source != AddonCatalogSource.COLLECTION || _uiState.value.activeCollection == collection)
                ) {
                    _uiState.value = _uiState.value.copy(
                        isCatalogLoading = false,
                        catalogError = "The addon catalog request timed out"
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (_uiState.value.catalogSource == source &&
                    (source != AddonCatalogSource.COLLECTION || _uiState.value.activeCollection == collection)
                ) {
                    _uiState.value = _uiState.value.copy(
                        isCatalogLoading = false,
                        catalogError = e.message ?: "Could not load the addon catalog"
                    )
                }
            }
        }
    }

    fun selectCatalogSource(source: AddonCatalogSource) {
        if (source == AddonCatalogSource.COLLECTION) return
        val current = _uiState.value
        if (current.catalogSource == source && current.catalogItems.isNotEmpty()) return
        _uiState.value = current.copy(activeCollection = null)
        loadCatalog(source, force = true)
    }

    fun selectCollection(collection: SavedAddonCollection) {
        val current = _uiState.value
        if (collection !in current.savedCollections) return
        if (current.catalogSource == AddonCatalogSource.COLLECTION &&
            current.activeCollection == collection && current.catalogItems.isNotEmpty()
        ) return
        _uiState.value = current.copy(
            catalogSource = AddonCatalogSource.COLLECTION,
            activeCollection = collection,
            catalogItems = emptyList(),
            catalogError = null
        )
        loadCatalog(AddonCatalogSource.COLLECTION, force = true)
    }

    fun retryCatalog() {
        loadCatalog(_uiState.value.catalogSource, force = true)
    }

    fun prepareInstall(url: String) {
        if (url.isBlank()) return
        val normalizedInput = normalizeInstallUrl(url)
        if (normalizedInput == null) {
            _uiState.value = _uiState.value.copy(
                error = "Use an HTTP/HTTPS manifest URL or a Stremio addon install link"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val uri = Uri.parse(normalizedInput)
                val encodedPath = uri.encodedPath.orEmpty()
                val manifestPath = if (encodedPath.trimEnd('/').endsWith("manifest.json", ignoreCase = true)) {
                    encodedPath
                } else {
                    encodedPath.trimEnd('/') + "/manifest.json"
                }
                // Rebuild only the path so query parameters/configuration tokens survive.
                val plainUrl = uri.buildUpon().encodedPath(manifestPath).build().toString()
                val validUrl = DebridAddonUrlHelper.withDebridKeyIfKnown(
                    plainUrl,
                    debridManager.connectedProvider.value,
                    debridManager.getApiKey()
                )
                val debridKeyInjected = validUrl != plainUrl
                val manifest = repository.fetchManifest(validUrl)
                val displayableCatalogs = manifest.catalogs.orEmpty().filter { catalog ->
                    val isStandardType = catalog.type == "movie" || catalog.type == "series" || catalog.type == "channel" || catalog.type == "tv"
                    isStandardType
                }
                if (displayableCatalogs.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pendingInstall = AddonInstallConfig(validUrl, manifest.name, displayableCatalogs.size, debridKeyInjected)
                    )
                } else {
                    confirmInstall(validUrl, false, false, false)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                _events.send(AddonEvent.InstallationFailure(e.message ?: "Error"))
            }
        }
    }

    private fun normalizeInstallUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> trimmed
            "stremio" -> {
                if (uri.host.isNullOrBlank()) null
                else uri.buildUpon().scheme("https").build().toString()
            }
            else -> null
        }
    }

    fun confirmInstall(url: String, home: Boolean, movies: Boolean, series: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, pendingInstall = null)
            try {
                repository.installAddonWithConfig(url, home, movies, series)
                persistProfileState()
                _uiState.value = _uiState.value.copy(isLoading = false)
                _events.send(AddonEvent.InstallationSuccess)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                _events.send(AddonEvent.InstallationFailure(e.message ?: "Error"))
            }
        }
    }

    fun cancelInstall() {
        _uiState.value = _uiState.value.copy(pendingInstall = null)
    }

    fun deleteAddon(transportUrl: String) {
        viewModelScope.launch {
            try {
                repository.deleteAddon(transportUrl)
                persistProfileState()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to remove addon")
            }
        }
    }

    /** Connected debrid services and keys (primary first), for pre-filling Torrentio's configuration. */
    fun connectedDebrid(): List<Pair<com.hereliesaz.illumera.data.model.debrid.DebridProvider, String>> =
        debridManager.connectedKeys()

    /** The installed Torrentio's URL, if any (its configuration lives in the URL). */
    fun installedTorrentioUrl(): String? = _uiState.value.addons
        .firstOrNull { com.hereliesaz.illumera.data.debrid.TorrentioConfig.isTorrentio(it.transportUrl) }
        ?.transportUrl

    /**
     * Replaces the installed Torrentio with [config]'s URL. The new install is written
     * before the old one is removed, so a failure leaves the previous configuration intact.
     */
    fun saveTorrentio(config: com.hereliesaz.illumera.data.debrid.TorrentioConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val manifestUrl = config.manifestUrl()
                val newTransport = manifestUrl.removeSuffix("/manifest.json")
                val previous = _uiState.value.addons.filter {
                    com.hereliesaz.illumera.data.debrid.TorrentioConfig.isTorrentio(it.transportUrl) &&
                        it.transportUrl.trimEnd('/') != newTransport
                }
                repository.installAddonWithConfig(
                    manifestUrl, home = false, movies = false, series = false,
                    fallbackManifest = profileConfigurationManager.bakedManifest(BakedAddons.TORRENTIO_URL)
                )
                previous.forEach { repository.deleteAddon(it.transportUrl) }
                persistProfileState()
                _uiState.value = _uiState.value.copy(isLoading = false)
                _events.send(AddonEvent.InstallationSuccess)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Failed to save Torrentio")
            }
        }
    }

    fun setAddonEnabled(transportUrl: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setAddonEnabled(transportUrl, enabled)
                persistProfileState()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to update addon")
            }
        }
    }

    fun renameAddon(transportUrl: String, newName: String) {
        // Apply optimistically so a moveAddon() firing before the Room Flow round-trips
        // this rename can't overwrite it with the stale nickname it read from _uiState.
        _uiState.value = _uiState.value.copy(
            addons = _uiState.value.addons.map {
                if (it.transportUrl == transportUrl) it.copy(nickname = newName) else it
            }
        )
        viewModelScope.launch { repository.renameAddon(transportUrl, newName); persistProfileState() }
    }

    fun moveAddon(addon: AddonEntity, direction: Int) {
        val currentList = _uiState.value.addons.toMutableList()
        val index = currentList.indexOfFirst { it.transportUrl == addon.transportUrl }
        if (index == -1) return
        val newIndex = index + direction
        if (newIndex !in currentList.indices) return

        Collections.swap(currentList, index, newIndex)
        val updated = currentList.mapIndexed { i, item -> item.copy(sortOrder = i) }
        _uiState.value = _uiState.value.copy(addons = updated)
        viewModelScope.launch { repository.updateAddons(updated); persistProfileState() }
    }
}
