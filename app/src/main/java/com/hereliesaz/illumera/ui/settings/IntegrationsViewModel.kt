package com.hereliesaz.illumera.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.auth.StremioConnectionState
import com.hereliesaz.illumera.data.auth.pollSocialLogin
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.torrentclaw.TorrentClawIntegration
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.debrid.DebridConnection
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.profile.ProfileMutationCoordinator
import com.hereliesaz.illumera.data.remote.StremioAuthError
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.sync.LibraryRefreshService
import com.hereliesaz.illumera.data.trakt.DeviceAuthState
import com.hereliesaz.illumera.data.trakt.TraktAuthManager
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import com.hereliesaz.illumera.data.wutch.WutchManager
import com.hereliesaz.illumera.ui.profiles.ProfileAssets
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import com.hereliesaz.illumera.data.profile.BakedAddons
import javax.inject.Inject
import com.hereliesaz.illumera.data.auth.StremioLibrarySyncManager
import com.hereliesaz.illumera.ui.addons.CINEMETA_TRANSPORT_URL
import com.hereliesaz.illumera.ui.addons.normalizeTransportUrl

sealed class IntegrationsEvent {
    object LoginSuccess : IntegrationsEvent()
    data class LoginError(val message: String) : IntegrationsEvent()
    data class SyncComplete(val count: Int) : IntegrationsEvent()
    data class PushComplete(val count: Int) : IntegrationsEvent()
    /** Ask the user before replacing the account's addon collection with [count] local addons. */
    data class ConfirmPushAddons(val count: Int) : IntegrationsEvent()
    /** Ask the user before an addon import that removes [removeCount] local addons. */
    data class ConfirmSyncAddons(val removeCount: Int) : IntegrationsEvent()
    object Disconnected : IntegrationsEvent()
    data class StremioSyncReset(val removedAddons: Int) : IntegrationsEvent()
    data class DebridConnected(val provider: DebridProvider) : IntegrationsEvent()
    data class DebridError(val message: String) : IntegrationsEvent()
    data class ExternalUrlReady(val title: String, val url: String) : IntegrationsEvent()
    data class WutchConnected(val username: String) : IntegrationsEvent()
    data class WutchError(val message: String) : IntegrationsEvent()
}

/** State machine for the "Login with Facebook" flow — mirrors Trakt's DeviceAuthState. */
sealed class FacebookLoginState {
    object Idle : FacebookLoginState()
    data class WaitingForUser(val url: String) : FacebookLoginState()
    object Success : FacebookLoginState()
    data class Error(val message: String) : FacebookLoginState()
}

sealed class AppleLoginState {
    object Idle : AppleLoginState()
    data class WaitingForUser(val url: String) : AppleLoginState()
    object Success : AppleLoginState()
    data class Error(val message: String) : AppleLoginState()
}

data class IntegrationsUiState(
    val connectionState: StremioConnectionState = StremioConnectionState.Disconnected,
    val isLoading: Boolean = false,
    val facebookLoginState: FacebookLoginState = FacebookLoginState.Idle,
    val appleLoginState: AppleLoginState = AppleLoginState.Idle,
    val tmdbEnabled: Boolean = false,
    val tmdbLanguage: String = "",
    val traktConnected: Boolean = false,
    val traktAuthState: DeviceAuthState = DeviceAuthState.Idle,
    /** Connected debrid services, primary first. */
    val debridConnections: List<DebridConnection> = emptyList(),
    val debridConnecting: Boolean = false,
    val wutchUsername: String? = null,
    val wutchBusy: Boolean = false,
    val torrentClawEnabled: Boolean = false,
    val torrentClawHasKey: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IntegrationsViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val profileMutationCoordinator: ProfileMutationCoordinator,
    private val dao: AddonDao,
    private val traktAuthManager: TraktAuthManager,
    private val traktSyncManager: TraktSyncManager,
    private val debridManager: DebridManager,
    private val stremioLibrarySyncManager: StremioLibrarySyncManager,
    private val wutchManager: WutchManager,
    private val torrentClaw: TorrentClawIntegration
) : ViewModel() {

    private val _uiState = MutableStateFlow(IntegrationsUiState())
    val uiState: StateFlow<IntegrationsUiState> = _uiState.asStateFlow()

    private val _events = Channel<IntegrationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var facebookLoginJob: Job? = null
    private var appleLoginJob: Job? = null
    private var addonSyncJob: Job? = null
    private var addonPushJob: Job? = null

    init {
        // Observe connection state
        viewModelScope.launch {
            stremioAuthManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
        // Observe Trakt connection state
        viewModelScope.launch {
            traktAuthManager.isConnected.collect { connected ->
                _uiState.update { it.copy(traktConnected = connected) }
            }
        }
        viewModelScope.launch {
            traktAuthManager.authState.collect { authState ->
                _uiState.update { it.copy(traktAuthState = authState) }
                // Push existing local watchlist + pull from Trakt after first auth
                if (authState is DeviceAuthState.Success) {
                    traktSyncManager.initialSync()
                }
            }
        }
        viewModelScope.launch {
            wutchManager.refreshConnectionState()
            wutchManager.username.collect { username ->
                _uiState.update { it.copy(wutchUsername = username) }
            }
        }
        // Observe Debrid connection state
        viewModelScope.launch {
            debridManager.connections.collect { connections ->
                _uiState.update { it.copy(debridConnections = connections) }
            }
        }
        // Both the enable flag and optional encrypted API key belong to the active profile.
        viewModelScope.launch {
            profileConfigurationManager.activeProfileId.collect {
                refreshTorrentClaw()
            }
        }
        // Follow the active profile's TMDB settings (this VM outlives profile switches)
        viewModelScope.launch {
            profileConfigurationManager.activeProfileId
                .flatMapLatest { id -> if (id == null) flowOf(null) else dao.getProfileFlow(id) }
                .filterNotNull()
                .map { it.tmdbEnabled to it.tmdbLanguage }
                .distinctUntilChanged()
                .collect { (enabled, language) ->
                    _uiState.update { it.copy(tmdbEnabled = enabled, tmdbLanguage = language) }
                }
        }
    }

    /**
     * Mirrors Stremio's own behavior: once an account is connected, its avatar
     * (a Facebook photo, for accounts created via Facebook login) becomes this
     * profile's avatar.
     */
    private suspend fun applyStremioAvatarToProfile() {
        val avatarUrl = stremioAuthManager.getStoredAvatarUrl() ?: return
        val profileId = profileConfigurationManager.getLastActiveProfileId() ?: return
        profileMutationCoordinator.update(profileId) {
            it.copy(avatarRef = ProfileAssets.urlAvatarRef(avatarUrl))
        }
    }

    fun updateTmdbEnabled(enabled: Boolean) {
        _uiState.update { it.copy(tmdbEnabled = enabled) }
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: return@launch
            profileMutationCoordinator.update(profileId) { it.copy(tmdbEnabled = enabled) }
        }
    }

    fun updateTmdbLanguage(language: String) {
        _uiState.update { it.copy(tmdbLanguage = language) }
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: return@launch
            profileMutationCoordinator.update(profileId) { it.copy(tmdbLanguage = language) }
        }
    }

    /**
     * Logs in to Stremio with email/password.
     * On success, triggers addon sync automatically.
     */
    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = stremioAuthManager.login(email, password)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    // Auto-sync addons and continue-watching after login
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false) }
                    val message = when (error) {
                        is StremioAuthError.InvalidCredentials -> "Invalid email or password"
                        is StremioAuthError.NetworkError -> "Network error: ${error.message}"
                        else -> error.message ?: "Unknown error"
                    }
                    _events.send(IntegrationsEvent.LoginError(message))
                }
            )
        }
    }

    /** Creates and immediately connects a new Stremio account. */
    fun register(email: String, password: String, marketing: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = stremioAuthManager.register(email, password, marketing)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false) }
                    val message = when (error) {
                        is StremioAuthError.NetworkError -> "Network error: ${error.message}"
                        else -> error.message ?: "Could not create Stremio account"
                    }
                    _events.send(IntegrationsEvent.LoginError(message))
                }
            )
        }
    }

    /**
     * Starts a "Login with Facebook" flow: shows a URL (as a QR code) the
     * user opens in a browser to complete Facebook OAuth on Stremio's own
     * servers, then polls for completion and logs in with the resulting
     * one-time token.
     */
    fun startFacebookLogin() {
        facebookLoginJob?.cancel()
        val (state, url) = stremioAuthManager.startFacebookLogin()
        _uiState.update { it.copy(facebookLoginState = FacebookLoginState.WaitingForUser(url)) }

        facebookLoginJob = viewModelScope.launch {
            val result = pollSocialLogin { stremioAuthManager.completeFacebookLogin(state) }
            if (result == null) {
                _uiState.update { it.copy(
                    facebookLoginState = FacebookLoginState.Error("Facebook login timed out. Please try again.")
                ) }
                return@launch
            }
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(facebookLoginState = FacebookLoginState.Success) }
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    val message = error.message ?: "Facebook login failed"
                    _uiState.update { it.copy(facebookLoginState = FacebookLoginState.Error(message)) }
                }
            )
        }
    }

    fun resetFacebookLoginState() {
        facebookLoginJob?.cancel()
        facebookLoginJob = null
        _uiState.update { it.copy(facebookLoginState = FacebookLoginState.Idle) }
    }

    fun startAppleLogin() {
        appleLoginJob?.cancel()
        val (state, url) = stremioAuthManager.startAppleLogin()
        _uiState.update { it.copy(appleLoginState = AppleLoginState.WaitingForUser(url)) }

        appleLoginJob = viewModelScope.launch {
            val result = pollSocialLogin { stremioAuthManager.completeAppleLogin(state) }
            if (result == null) {
                _uiState.update { it.copy(
                    appleLoginState = AppleLoginState.Error("Apple login timed out. Please try again.")
                ) }
                return@launch
            }
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(appleLoginState = AppleLoginState.Success) }
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    val message = error.message ?: "Apple login failed"
                    _uiState.update { it.copy(appleLoginState = AppleLoginState.Error(message)) }
                }
            )
        }
    }

    fun resetAppleLoginState() {
        appleLoginJob?.cancel()
        appleLoginJob = null
        _uiState.update { it.copy(appleLoginState = AppleLoginState.Idle) }
    }

    fun exportStremioData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            stremioAuthManager.getDataExportUrl().fold(
                onSuccess = { url ->
                    _uiState.update { it.copy(isLoading = false) }
                    _events.send(IntegrationsEvent.ExternalUrlReady("Stremio Data Export", url))
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false) }
                    _events.send(IntegrationsEvent.LoginError(error.message ?: "Could not export Stremio data"))
                }
            )
        }
    }

    fun openStremioCalendar() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            stremioAuthManager.getCalendarUrl().fold(
                onSuccess = { url ->
                    _uiState.update { it.copy(isLoading = false) }
                    _events.send(IntegrationsEvent.ExternalUrlReady("Stremio Calendar", url))
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false) }
                    _events.send(IntegrationsEvent.LoginError(error.message ?: "Could not open Stremio calendar"))
                }
            )
        }
    }

    /**
     * Starts the user-visible foreground refresh used for connected library
     * network processing. The service syncs Stremio Continue Watching,
     * refreshes addon data/manifests, and syncs Trakt state when connected.
     * It keeps running if the user navigates away and exposes a cancel action
     * in its ongoing notification.
     */
    fun syncLibrary() {
        LibraryRefreshService.start(applicationContext)
    }

    /**
     * Pushes the locally-installed addon collection up to the Stremio
     * account, replacing what's stored there.
     */
    fun pushAddonsToStremio() {
        if (addonPushJob?.isActive == true) return
        addonPushJob = viewModelScope.launch {
            val urls = addonRepository.getAddons().firstOrNull()?.map { it.transportUrl } ?: emptyList()
            // Never wipe the account's collection with an empty push.
            if (urls.isEmpty()) {
                _events.send(IntegrationsEvent.LoginError("No local addons to push."))
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            try {
                val result = stremioAuthManager.pushAddonCollection(urls)
                result.fold(
                    onSuccess = { _events.send(IntegrationsEvent.PushComplete(urls.size)) },
                    onFailure = { error -> _events.send(IntegrationsEvent.LoginError(error.message ?: "Push failed")) }
                )
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Emits [IntegrationsEvent.ConfirmPushAddons] so the UI can confirm before [pushAddonsToStremio]. */
    fun requestPushAddons() {
        viewModelScope.launch {
            val count = addonRepository.getAddons().firstOrNull()?.size ?: 0
            if (count == 0) {
                _events.send(IntegrationsEvent.LoginError("No local addons to push."))
            } else {
                _events.send(IntegrationsEvent.ConfirmPushAddons(count))
            }
        }
    }

    /**
     * Manual addon import: if it would remove local addons, emits
     * [IntegrationsEvent.ConfirmSyncAddons] with the count first; otherwise syncs directly.
     */
    fun requestSyncAddons() {
        if (addonSyncJob?.isActive == true) return
        viewModelScope.launch {
            val entries = stremioAuthManager.fetchAddons().getOrElse { error ->
                _events.send(IntegrationsEvent.LoginError(error.message ?: "Could not import Stremio addons."))
                return@launch
            }
            val remoteSet = entries.map { normalizeTransportUrl(it.transportUrl) }.filter { it.isNotBlank() }.toSet()
            val removeCount = if (remoteSet.isEmpty()) 0 else {
                addonRepository.getAddons().first().count { addon ->
                    val localUrl = normalizeTransportUrl(addon.transportUrl)
                    !BakedAddons.isBaked(localUrl) && localUrl !in remoteSet
                }
            }
            if (removeCount > 0) {
                _events.send(IntegrationsEvent.ConfirmSyncAddons(removeCount))
            } else {
                syncAddons()
            }
        }
    }

    /**
     * Syncs addons from the connected Stremio account.
     * Uses the stored authKey - does not require password.
     */
    fun syncAddons() {
        if (addonSyncJob?.isActive == true) return
        val initiatingProfileId = profileConfigurationManager.getLastActiveProfileId() ?: return
        addonSyncJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
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
                    for (transportUrl in remoteUrls) {
                        val existing = currentByUrl[transportUrl]
                        val placeholder = existing != null && !existing.isEnabled &&
                            existing.catalogsJson.trim() == "[]" &&
                            !existing.supportsMeta && !existing.supportsStream
                        if (existing != null && !placeholder) continue
                        try {
                            addonRepository.installAddon("$transportUrl/manifest.json")
                            installedCount++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            com.hereliesaz.illumera.crash.AppErrors.w("Integrations", "Could not import Stremio addon $transportUrl", e)
                        }
                    }

                    val remoteSet = remoteUrls.toSet()
                    // An empty remote collection (new account, or a server hiccup) must not
                    // wipe every local addon; only prune when the account lists some.
                    if (remoteSet.isNotEmpty()) current.forEach { addon ->
                        val localUrl = normalizeTransportUrl(addon.transportUrl)
                        if (!BakedAddons.isBaked(localUrl) && localUrl !in remoteSet) {
                            try {
                                addonRepository.deleteAddon(addon.transportUrl)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                com.hereliesaz.illumera.crash.AppErrors.w("Integrations", "Could not remove local addon absent from Stremio: $localUrl", e)
                            }
                        }
                    }

                    var refreshed = addonRepository.getAddons().first()
                    if (refreshed.none { normalizeTransportUrl(it.transportUrl) == CINEMETA_TRANSPORT_URL }) {
                        runCatching { addonRepository.installAddon("$CINEMETA_TRANSPORT_URL/manifest.json") }
                        refreshed = addonRepository.getAddons().first()
                    }

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
                    _events.send(IntegrationsEvent.SyncComplete(installedCount))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is StremioAuthError.InvalidCredentials -> "Session expired. Please reconnect."
                    is StremioAuthError.NetworkError -> "Network error: ${error.message}"
                    else -> error.message ?: "Could not import Stremio addons."
                }
                _events.send(IntegrationsEvent.LoginError(message))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Disconnects from Stremio.
     */
    fun disconnect() {
        val profileId = profileConfigurationManager.getLastActiveProfileId() ?: return
        stremioAuthManager.clearCredentialsForProfile(profileId)
        stremioAuthManager.disconnect()
        viewModelScope.launch {
            _events.send(IntegrationsEvent.Disconnected)
        }
    }

    /**
     * Undoes Stremio sync on this profile so it can be set up again from scratch: signs out,
     * forgets the library-sync baseline (so a fresh sign-in merges instead of pushing
     * deletions), and removes every addon except the baked ones (Cinemeta, Streaming Catalogs). Local watch progress stays.
     */
    fun resetStremioSync() {
        val profileId = profileConfigurationManager.getLastActiveProfileId() ?: return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            var removed = 0
            try {
                profileConfigurationManager.withActiveProfileRuntime(profileId) {
                    stremioAuthManager.clearCredentialsForProfile(profileId)
                    stremioAuthManager.disconnect()
                    stremioLibrarySyncManager.clearSyncState(profileId)
                    val addons = addonRepository.getAddons().first()
                    addons.filter { !BakedAddons.isBaked(normalizeTransportUrl(it.transportUrl)) }.forEach { addon ->
                        addonRepository.deleteAddon(addon.transportUrl)
                        removed++
                    }
                    if (addons.none { normalizeTransportUrl(it.transportUrl) == CINEMETA_TRANSPORT_URL }) {
                        runCatching { addonRepository.installAddon("$CINEMETA_TRANSPORT_URL/manifest.json") }
                    }
                    profileConfigurationManager.saveRuntimeStateWithinActiveRuntime(profileId)
                    profileConfigurationManager.resetStartupCapture()
                }
                _events.send(IntegrationsEvent.StremioSyncReset(removed))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("Integrations", "Stremio sync reset failed", e)
                _events.send(IntegrationsEvent.LoginError("Couldn't reset Stremio sync: ${e.message ?: "unknown error"}"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // ── Trakt ──

    fun startTraktAuth() {
        traktAuthManager.startDeviceAuth()
    }

    fun disconnectTrakt() {
        viewModelScope.launch { traktAuthManager.disconnect() }
    }

    fun resetTraktAuthState() {
        traktAuthManager.resetAuthState()
    }

    // ── Debrid ──

    fun connectDebrid(provider: DebridProvider, apiKey: String) {
        // Set synchronously so a second tap (or a phone-sent key) can't start a parallel connect.
        if (_uiState.value.debridConnecting) return
        _uiState.update { it.copy(debridConnecting = true) }
        viewModelScope.launch {
            try {
                when (val result = debridManager.connect(provider, apiKey)) {
                    is DebridResult.Success -> _events.send(IntegrationsEvent.DebridConnected(provider))
                    is DebridResult.Failure -> _events.send(IntegrationsEvent.DebridError(result.message))
                }
            } finally {
                _uiState.update { it.copy(debridConnecting = false) }
            }
        }
    }

    fun disconnectDebrid(provider: DebridProvider) {
        debridManager.disconnect(provider)
    }

    fun setPrimaryDebrid(provider: DebridProvider) {
        debridManager.setPrimary(provider)
    }

    // ── wutch.tv ──

    /** Signs in with email and password, or with [apiKey] when given, then imports. */
    private fun refreshTorrentClaw() {
        _uiState.update {
            it.copy(
                torrentClawEnabled = torrentClaw.isEnabled(),
                torrentClawHasKey = torrentClaw.hasKey()
            )
        }
    }

    fun setTorrentClawEnabled(enabled: Boolean) {
        torrentClaw.setEnabled(enabled)
        refreshTorrentClaw()
    }

    /** The optional search key stays in encrypted storage and is never exposed to UI state. */
    fun saveTorrentClawKey(key: String): Boolean {
        val saved = torrentClaw.saveKey(key)
        refreshTorrentClaw()
        return saved
    }

    fun clearTorrentClawKey() {
        torrentClaw.clearKey()
        refreshTorrentClaw()
    }

    fun connectWutch(email: String, password: String, apiKey: String?) {
        if (_uiState.value.wutchBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(wutchBusy = true) }
            try {
                val username = if (!apiKey.isNullOrBlank()) wutchManager.signInWithKey(apiKey)
                else wutchManager.signIn(email, password)
                _events.send(IntegrationsEvent.WutchConnected(username))
                wutchManager.importAll()
                    .onFailure { _events.send(IntegrationsEvent.WutchError(it.message ?: "wutch.tv sync failed.")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _events.send(IntegrationsEvent.WutchError(e.message ?: "Couldn't sign in to wutch.tv."))
            } finally {
                _uiState.update { it.copy(wutchBusy = false) }
            }
        }
    }

    fun syncWutch() {
        if (_uiState.value.wutchBusy) return
        _uiState.update { it.copy(wutchBusy = true) }
        viewModelScope.launch {
            try {
                wutchManager.importAll()
                    .onFailure { _events.send(IntegrationsEvent.WutchError(it.message ?: "wutch.tv sync failed.")) }
            } finally {
                _uiState.update { it.copy(wutchBusy = false) }
            }
        }
    }

    fun disconnectWutch() {
        viewModelScope.launch { wutchManager.disconnect() }
    }
}
