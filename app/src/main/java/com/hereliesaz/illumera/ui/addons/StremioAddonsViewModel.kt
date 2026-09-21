package com.hereliesaz.illumera.ui.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.auth.StremioConnectionState
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CINEMETA_TRANSPORT_URL = "https://v3-cinemeta.strem.io"

data class StremioAddonsUiState(
    val isSyncing: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class StremioAddonsViewModel @Inject constructor(
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository,
    private val profileConfigurationManager: ProfileConfigurationManager
) : ViewModel() {

    val connectionState: StateFlow<StremioConnectionState> = stremioAuthManager.connectionState

    private val _uiState = MutableStateFlow(StremioAddonsUiState())
    val uiState: StateFlow<StremioAddonsUiState> = _uiState.asStateFlow()

    /**
     * Reconciles Illumera's active-profile runtime with the connected Stremio
     * account after the user manages addons in Stremio's official UI.
     *
     * Stremio is authoritative for the addon set and ordering. Existing Illumera-
     * specific configuration for an unchanged transport URL is preserved because
     * those rows are left in place; only missing/removed addons are installed or
     * deleted. Cinemeta is retained as Illumera's metadata baseline even if the
     * account endpoint omits Stremio's built-in addon from its mutable collection.
     */
    fun syncFromStremio() {
        if (connectionState.value !is StremioConnectionState.Connected) {
            _uiState.value = StremioAddonsUiState(
                message = "Connect a Stremio account in Settings → Integrations first.",
                error = "Stremio is not connected."
            )
            return
        }

        val initiatingProfileId = profileConfigurationManager.getLastActiveProfileId()
        if (initiatingProfileId == null) {
            _uiState.value = StremioAddonsUiState(
                message = "Select a profile before syncing Stremio addons.",
                error = "No active profile is selected."
            )
            return
        }

        if (_uiState.value.isSyncing) return
        _uiState.value = StremioAddonsUiState(isSyncing = true)

        viewModelScope.launch {
            try {
                profileConfigurationManager.withActiveProfileRuntime(initiatingProfileId) {
                    val entries = stremioAuthManager.fetchAddons().getOrElse { throw it }
                    val remoteUrls = entries
                        .map { normalizeTransportUrl(it.transportUrl) }
                        .filter { it.isNotBlank() }
                        .distinct()

                    val current = addonRepository.getAddons().first()
                    val currentByUrl = current.associateBy { normalizeTransportUrl(it.transportUrl) }
                    var installedCount = 0
                    var removedCount = 0
                    var failedCount = 0

                    for (transportUrl in remoteUrls) {
                        if (currentByUrl.containsKey(transportUrl)) continue
                        try {
                            addonRepository.installAddon("$transportUrl/manifest.json")
                            installedCount++
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            failedCount++
                        }
                    }

                    val remoteSet = remoteUrls.toSet()
                    current.forEach { addon ->
                        val localUrl = normalizeTransportUrl(addon.transportUrl)
                        if (localUrl != CINEMETA_TRANSPORT_URL && localUrl !in remoteSet) {
                            try {
                                addonRepository.deleteAddon(addon.transportUrl)
                                removedCount++
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                failedCount++
                            }
                        }
                    }

                    val refreshed = addonRepository.getAddons().first()
                    val remoteOrder = remoteUrls.withIndex().associate { (index, url) -> url to index }
                    val ordered = refreshed
                        .sortedWith(
                            compareBy(
                                { remoteOrder[normalizeTransportUrl(it.transportUrl)] ?: Int.MAX_VALUE },
                                { if (normalizeTransportUrl(it.transportUrl) == CINEMETA_TRANSPORT_URL) 0 else 1 },
                                { it.name.lowercase() }
                            )
                        )
                        .mapIndexed { index, addon -> addon.copy(sortOrder = index) }

                    addonRepository.updateAddons(ordered)
                    profileConfigurationManager.saveRuntimeStateWithinActiveRuntime(initiatingProfileId)
                    profileConfigurationManager.resetStartupCapture()

                    val summary = buildString {
                        append("Synced ${remoteUrls.size} Stremio addon")
                        if (remoteUrls.size != 1) append('s')
                        if (installedCount > 0 || removedCount > 0) {
                            append(" · +$installedCount / -$removedCount")
                        }
                        if (failedCount > 0) {
                            append(" · $failedCount could not be reconciled")
                        }
                    }

                    _uiState.value = StremioAddonsUiState(
                        isSyncing = false,
                        message = summary,
                        error = if (failedCount > 0) "Some addons could not be refreshed." else null
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.value = StremioAddonsUiState(isSyncing = false)
                throw ce
            } catch (error: Exception) {
                _uiState.value = StremioAddonsUiState(
                    isSyncing = false,
                    message = error.message ?: "Could not sync addons from Stremio.",
                    error = error.message ?: "Could not sync addons from Stremio."
                )
            }
        }
    }
}

internal fun normalizeTransportUrl(raw: String): String =
    raw.trim().removeSuffix("/manifest.json").trimEnd('/')
