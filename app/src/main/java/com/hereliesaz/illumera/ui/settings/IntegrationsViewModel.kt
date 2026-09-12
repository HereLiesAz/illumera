package com.hereliesaz.illumera.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.auth.StremioConnectionState
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.StremioAddonItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.StremioAuthError
import com.hereliesaz.illumera.data.repository.AddonRepository
import com.hereliesaz.illumera.data.sync.LibraryRefreshService
import com.hereliesaz.illumera.data.trakt.DeviceAuthState
import com.hereliesaz.illumera.data.trakt.TraktAuthManager
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import com.hereliesaz.illumera.ui.profiles.ProfileAssets
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class IntegrationsEvent {
    object LoginSuccess : IntegrationsEvent()
    data class LoginError(val message: String) : IntegrationsEvent()
    data class SyncComplete(val count: Int) : IntegrationsEvent()
    object Disconnected : IntegrationsEvent()
    data class DebridConnected(val provider: DebridProvider) : IntegrationsEvent()
    data class DebridError(val message: String) : IntegrationsEvent()
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
    val pendingAddons: List<StremioAddonItem>? = null,
    val facebookLoginState: FacebookLoginState = FacebookLoginState.Idle,
    val appleLoginState: AppleLoginState = AppleLoginState.Idle,
    val tmdbEnabled: Boolean = false,
    val tmdbLanguage: String = "",
    val traktConnected: Boolean = false,
    val traktAuthState: DeviceAuthState = DeviceAuthState.Idle,
    val debridProvider: DebridProvider? = null,
    val debridUsername: String? = null,
    val debridConnecting: Boolean = false
)

@HiltViewModel
class IntegrationsViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val dao: AddonDao,
    private val traktAuthManager: TraktAuthManager,
    private val traktSyncManager: TraktSyncManager,
    private val debridManager: DebridManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(IntegrationsUiState())
    val uiState: StateFlow<IntegrationsUiState> = _uiState.asStateFlow()

    private val _events = Channel<IntegrationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var facebookLoginJob: Job? = null
    private var appleLoginJob: Job? = null

    init {
        // Observe connection state
        viewModelScope.launch {
            stremioAuthManager.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }
        // Observe Trakt connection state
        viewModelScope.launch {
            traktAuthManager.isConnected.collect { connected ->
                _uiState.value = _uiState.value.copy(traktConnected = connected)
            }
        }
        viewModelScope.launch {
            traktAuthManager.authState.collect { authState ->
                _uiState.value = _uiState.value.copy(traktAuthState = authState)
                // Push existing local watchlist + pull from Trakt after first auth
                if (authState is DeviceAuthState.Success) {
                    traktSyncManager.initialSync()
                }
            }
        }
        // Observe Debrid connection state
        viewModelScope.launch {
            debridManager.connectedProvider.collect { provider ->
                _uiState.value = _uiState.value.copy(debridProvider = provider)
            }
        }
        viewModelScope.launch {
            debridManager.connectedUsername.collect { username ->
                _uiState.value = _uiState.value.copy(debridUsername = username)
            }
        }
        // Load TMDB settings from active profile
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
            val profile = dao.getProfileById(profileId)
            if (profile != null) {
                _uiState.value = _uiState.value.copy(
                    tmdbEnabled = profile.tmdbEnabled,
                    tmdbLanguage = profile.tmdbLanguage
                )
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
        val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
        val profile = dao.getProfileById(profileId) ?: return
        dao.insertProfile(profile.copy(avatarRef = ProfileAssets.urlAvatarRef(avatarUrl)))
    }

    fun updateTmdbEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(tmdbEnabled = enabled)
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(tmdbEnabled = enabled))
        }
    }

    fun updateTmdbLanguage(language: String) {
        _uiState.value = _uiState.value.copy(tmdbLanguage = language)
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(tmdbLanguage = language))
        }
    }

    /**
     * Logs in to Stremio with email/password.
     * On success, triggers addon sync automatically.
     */
    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = stremioAuthManager.login(email, password)

            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    // Auto-sync addons and continue-watching after login
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
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
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = stremioAuthManager.register(email, password, marketing)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
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
        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.WaitingForUser(url))

        facebookLoginJob = viewModelScope.launch {
            val result = stremioAuthManager.completeFacebookLogin(state)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.Success)
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    val message = error.message ?: "Facebook login failed"
                    _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.Error(message))
                }
            )
        }
    }

    fun resetFacebookLoginState() {
        facebookLoginJob?.cancel()
        facebookLoginJob = null
        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.Idle)
    }

    fun startAppleLogin() {
        appleLoginJob?.cancel()
        val (state, url) = stremioAuthManager.startAppleLogin()
        _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.WaitingForUser(url))

        appleLoginJob = viewModelScope.launch {
            val result = stremioAuthManager.completeAppleLogin(state)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Success)
                    applyStremioAvatarToProfile()
                    _events.send(IntegrationsEvent.LoginSuccess)
                    syncAddons()
                    syncLibrary()
                },
                onFailure = { error ->
                    val message = error.message ?: "Apple login failed"
                    _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Error(message))
                }
            )
        }
    }

    fun resetAppleLoginState() {
        appleLoginJob?.cancel()
        appleLoginJob = null
        _uiState.value = _uiState.value.copy(appleLoginState = AppleLoginState.Idle)
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val urls = addonRepository.getAddons().firstOrNull()?.map { it.transportUrl } ?: emptyList()
            val result = stremioAuthManager.pushAddonCollection(urls)
            _uiState.value = _uiState.value.copy(isLoading = false)
            result.fold(
                onSuccess = { _events.send(IntegrationsEvent.SyncComplete(urls.size)) },
                onFailure = { error -> _events.send(IntegrationsEvent.LoginError(error.message ?: "Push failed")) }
            )
        }
    }

    /**
     * Syncs addons from the connected Stremio account.
     * Uses the stored authKey - does not require password.
     */
    fun syncAddons() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = stremioAuthManager.fetchAddons()

            result.fold(
                onSuccess = { entries ->
                    // Get currently installed addon URLs
                    val installedUrls = addonRepository.getAddons()
                        .firstOrNull()
                        ?.map { it.transportUrl }
                        ?.toSet()
                        ?: emptySet()

                    // Convert to StremioAddonItem with duplicate detection
                    val addonItems = entries.mapNotNull { entry ->
                        val manifest = entry.manifest ?: return@mapNotNull null
                        val transportUrl = entry.transportUrl.removeSuffix("/manifest.json").trimEnd('/')
                        val isInstalled = installedUrls.contains(transportUrl)

                        StremioAddonItem(
                            name = manifest.name ?: "Unknown Addon",
                            transportUrl = transportUrl,
                            description = manifest.description,
                            isSelected = !isInstalled,
                            isAlreadyInstalled = isInstalled
                        )
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pendingAddons = addonItems.ifEmpty { null }
                    )

                    if (addonItems.isEmpty()) {
                        _events.send(IntegrationsEvent.SyncComplete(0))
                    }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    val message = when (error) {
                        is StremioAuthError.InvalidCredentials -> "Session expired. Please reconnect."
                        is StremioAuthError.NetworkError -> "Network error: ${error.message}"
                        else -> error.message ?: "Unknown error"
                    }
                    _events.send(IntegrationsEvent.LoginError(message))
                }
            )
        }
    }

    /**
     * Imports selected addons to the local database.
     */
    fun importAddons(addons: List<StremioAddonItem>) {
        if (addons.isEmpty()) {
            _uiState.value = _uiState.value.copy(pendingAddons = null)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, pendingAddons = null)
            var successCount = 0

            for (addon in addons) {
                try {
                    val manifestUrl = if (addon.transportUrl.endsWith("manifest.json")) {
                        addon.transportUrl
                    } else {
                        "${addon.transportUrl}/manifest.json"
                    }
                    addonRepository.installAddon(manifestUrl)
                    successCount++
                } catch (e: Exception) {
                    // Continue with next addon
                }
            }

            profileConfigurationManager.saveActiveRuntimeState()
            profileConfigurationManager.resetStartupCapture()
            _uiState.value = _uiState.value.copy(isLoading = false)
            _events.send(IntegrationsEvent.SyncComplete(successCount))
        }
    }

    /**
     * Dismisses the import dialog.
     */
    fun dismissImportDialog() {
        _uiState.value = _uiState.value.copy(pendingAddons = null)
    }

    /**
     * Disconnects from Stremio.
     */
    fun disconnect() {
        val profileId = profileConfigurationManager.getLastActiveProfileId() ?: 1
        stremioAuthManager.clearCredentialsForProfile(profileId)
        stremioAuthManager.disconnect()
        viewModelScope.launch {
            _events.send(IntegrationsEvent.Disconnected)
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(debridConnecting = true)
            when (val result = debridManager.connect(provider, apiKey)) {
                is DebridResult.Success -> {
                    _uiState.value = _uiState.value.copy(debridConnecting = false)
                    _events.send(IntegrationsEvent.DebridConnected(provider))
                }
                is DebridResult.Failure -> {
                    _uiState.value = _uiState.value.copy(debridConnecting = false)
                    _events.send(IntegrationsEvent.DebridError(result.message))
                }
            }
        }
    }

    fun disconnectDebrid() {
        debridManager.disconnect()
    }
}
