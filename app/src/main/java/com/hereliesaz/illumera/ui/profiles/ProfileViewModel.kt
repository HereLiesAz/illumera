package com.hereliesaz.illumera.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.queue.QueueManager
import com.hereliesaz.illumera.data.profile.ProfileMutationCoordinator
import com.hereliesaz.illumera.data.remote.StremioAuthError
import com.hereliesaz.illumera.data.trakt.TraktAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class SetupSocialProvider(val displayName: String) {
    FACEBOOK("Facebook"),
    APPLE("Apple")
}

sealed class SetupSocialLoginState {
    object Idle : SetupSocialLoginState()
    data class WaitingForUser(val provider: SetupSocialProvider, val url: String) : SetupSocialLoginState()
    data class Error(val provider: SetupSocialProvider, val message: String) : SetupSocialLoginState()
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val profileMutationCoordinator: ProfileMutationCoordinator,
    private val stremioAuthManager: StremioAuthManager,
    private val debridManager: DebridManager,
    private val traktAuthManager: TraktAuthManager,
    private val queueManager: QueueManager
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProfileEntity>>(emptyList())
    val profiles: StateFlow<List<ProfileEntity>> = _profiles

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _wizardStep = MutableStateFlow(0)
    val wizardStep: StateFlow<Int> = _wizardStep

    private val _isInitializingProfile = MutableStateFlow(false)
    val isInitializingProfile: StateFlow<Boolean> = _isInitializingProfile

    private val _setupSocialLoginState = MutableStateFlow<SetupSocialLoginState>(SetupSocialLoginState.Idle)
    val setupSocialLoginState: StateFlow<SetupSocialLoginState> = _setupSocialLoginState
    private var setupSocialLoginJob: Job? = null

    // WIZARD DATA
    var tempName = ""
    var tempAvatarRef = "avatar_1"
    var tempThemeId = "illumera"

    private var editingProfileId: Int? = null

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            dao.getProfiles().collect { list ->
                _profiles.value = list
                _isLoading.value = false
            }
        }
    }

    // --- WIZARD ACTIONS ---

    fun startWizard() {
        editingProfileId = null
        tempName = ""
        tempAvatarRef = "avatar_1"
        tempThemeId = "illumera"
        _wizardStep.value = 1
    }

    fun startEditWizard(profile: ProfileEntity) {
        editingProfileId = profile.id
        tempName = profile.name
        tempAvatarRef = profile.avatarRef
        tempThemeId = profile.themeId
        _wizardStep.value = 1
    }

    fun cancelWizard() {
        _wizardStep.value = 0
        editingProfileId = null
    }

    fun setWizardName(name: String) {
        tempName = name
        _wizardStep.value = 2
    }

    fun setWizardAvatar(avatarKey: String) {
        tempAvatarRef = avatarKey
        _wizardStep.value = 3
    }

    fun setWizardTheme(themeId: String) {
        tempThemeId = themeId
        finishWizard()
    }

    private fun finishWizard() {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profileIdBeingEdited = editingProfileId
            if (profileIdBeingEdited != null) {
                var previousAvatarRef: String? = null
                profileMutationCoordinator.update(profileIdBeingEdited) { current ->
                    previousAvatarRef = current.avatarRef
                    current.copy(
                        name = tempName,
                        avatarRef = tempAvatarRef,
                        themeId = tempThemeId
                    )
                }
                if (previousAvatarRef != null && previousAvatarRef != tempAvatarRef) {
                    deleteCustomAvatarFile(previousAvatarRef)
                }
            } else {
                val profileId = dao.insertProfile(
                    ProfileEntity(
                        name = tempName,
                        avatarRef = tempAvatarRef,
                        themeId = tempThemeId,
                        navPosition = "left",
                        homeTabLayout = "cinematic",
                        roundCorners = true
                    )
                ).toInt()
                if (profileId > 0) {
                    profileConfigurationManager.markPendingSetup(profileId)
                }
            }
            _wizardStep.value = 0
            editingProfileId = null
        }
    }

    fun needsInitialSetup(profileId: Int): Boolean {
        return profileConfigurationManager.needsInitialSetup(profileId)
    }

    fun initializeProfileFromScratch(profileId: Int, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            _isInitializingProfile.value = true
            try {
                profileConfigurationManager.initializeFromScratch(profileId)
                onComplete()
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    /**
     * Same as [initializeProfileFromScratch] but also logs in to Stremio with the given
     * credentials and folds the account's addons into the profile. [onResult] is always
     * called with `success = true` once the profile is initialized (whether or not the
     * Stremio login itself succeeded) — `success = false` only reports a Stremio-side
     * failure via `errorMessage` so the caller can let the user retry instead of moving on.
     */
    fun initializeProfileFromScratchWithStremio(
        profileId: Int,
        email: String,
        password: String,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            _isInitializingProfile.value = true
            try {
                val result = profileConfigurationManager.initializeFromScratchWithStremio(profileId, email, password)
                result.fold(
                    onSuccess = {
                        applyConnectedAccountAvatar(profileId, useAccountAvatar)
                        onResult(true, null)
                    },
                    onFailure = { error ->
                        preserveConnectedCredentialsAfterSetupFetchFailure(profileId, email, error)
                        onResult(false, setupErrorMessage(error))
                    }
                )
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    fun initializeProfileFromScratchWithFacebook(
        profileId: Int,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        startSetupSocialLogin(
            provider = SetupSocialProvider.FACEBOOK,
            profileId = profileId,
            useAccountAvatar = useAccountAvatar,
            onResult = onResult
        )
    }

    fun initializeProfileFromScratchWithApple(
        profileId: Int,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        startSetupSocialLogin(
            provider = SetupSocialProvider.APPLE,
            profileId = profileId,
            useAccountAvatar = useAccountAvatar,
            onResult = onResult
        )
    }

    private fun startSetupSocialLogin(
        provider: SetupSocialProvider,
        profileId: Int,
        useAccountAvatar: Boolean,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        setupSocialLoginJob?.cancel()

        val handoff = runCatching {
            when (provider) {
                SetupSocialProvider.FACEBOOK -> stremioAuthManager.startFacebookLogin()
                SetupSocialProvider.APPLE -> stremioAuthManager.startAppleLogin()
            }
        }.getOrElse { error ->
            val message = error.message ?: "Could not start ${provider.displayName} sign-in."
            _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
            onResult(false, message)
            return
        }

        val (state, url) = handoff
        _setupSocialLoginState.value = SetupSocialLoginState.WaitingForUser(provider, url)
        _isInitializingProfile.value = true

        setupSocialLoginJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val authResult = withTimeoutOrNull(5 * 60_000L) {
                    completeSetupSocialLogin(provider, state)
                }

                if (authResult == null) {
                    val message = "${provider.displayName} sign-in timed out. Please try again."
                    _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
                    onResult(false, message)
                    return@launch
                }

                authResult.fold(
                    onSuccess = {
                        val setupResult = profileConfigurationManager.initializeFromScratchWithConnectedStremio(profileId)
                        setupResult.fold(
                            onSuccess = {
                                applyConnectedAccountAvatar(profileId, useAccountAvatar)
                                _setupSocialLoginState.value = SetupSocialLoginState.Idle
                                onResult(true, null)
                            },
                            onFailure = { error ->
                                preserveConnectedCredentialsAfterSetupFetchFailure(profileId, null, error)
                                val message = setupErrorMessage(error)
                                _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
                                onResult(false, message)
                            }
                        )
                    },
                    onFailure = { error ->
                        val message = setupErrorMessage(error)
                        _setupSocialLoginState.value = SetupSocialLoginState.Error(provider, message)
                        onResult(false, message)
                    }
                )
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    /**
     * The Stremio service polls Facebook for ~25 seconds and Apple for ~50 seconds per
     * call. Repeat those bounded polls until our setup flow's five-minute deadline so
     * normal users are not failed while they are still completing OAuth on another device.
     */
    private suspend fun completeSetupSocialLogin(
        provider: SetupSocialProvider,
        state: String
    ): Result<String> {
        while (true) {
            val result = when (provider) {
                SetupSocialProvider.FACEBOOK -> stremioAuthManager.completeFacebookLogin(state)
                SetupSocialProvider.APPLE -> stremioAuthManager.completeAppleLogin(state)
            }
            if (result.isSuccess) return result

            val error = result.exceptionOrNull()
            val pollWindowExpired = error is StremioAuthError.NetworkError &&
                error.message.contains("timed out or was not completed", ignoreCase = true)
            if (!pollWindowExpired) return result
        }
    }

    fun resetSetupSocialLoginState() {
        setupSocialLoginJob?.cancel()
        setupSocialLoginJob = null
        _setupSocialLoginState.value = SetupSocialLoginState.Idle
        _isInitializingProfile.value = false
    }

    private suspend fun applyConnectedAccountAvatar(profileId: Int, enabled: Boolean) {
        resolveSetupAccountAvatarRef(
            enabled = enabled,
            stremioAvatarUrl = stremioAuthManager.getStoredAvatarUrl()
        )?.let { avatarRef ->
            var previousAvatarRef: String? = null
            profileMutationCoordinator.update(profileId) { current ->
                previousAvatarRef = current.avatarRef
                current.copy(avatarRef = avatarRef)
            }
            if (previousAvatarRef != null && previousAvatarRef != avatarRef) {
                deleteCustomAvatarFile(previousAvatarRef)
            }
        }
    }

    /**
     * Fresh-profile setup writes the default snapshot even when addon fetch fails. If
     * authentication already succeeded, keep those credentials profile-scoped so a
     * transient collection/network failure does not silently sign the user back out.
     */
    private fun preserveConnectedCredentialsAfterSetupFetchFailure(
        profileId: Int,
        expectedEmail: String?,
        error: Throwable
    ) {
        if (error is StremioAuthError.InvalidCredentials) return
        val authKey = stremioAuthManager.getStoredAuthKey() ?: return
        val storedEmail = stremioAuthManager.getStoredEmail() ?: return
        if (authKey.isBlank()) return
        if (expectedEmail != null && !storedEmail.equals(expectedEmail, ignoreCase = true)) return
        stremioAuthManager.saveCredentialsForProfile(profileId)
    }

    private fun setupErrorMessage(error: Throwable): String = when (error) {
        is StremioAuthError.InvalidCredentials -> "Invalid email or password."
        is StremioAuthError.NetworkError -> "Network error. Check your connection and try again."
        else -> error.message ?: "Couldn't connect to Stremio."
    }

    fun initializeProfileByCopy(targetProfileId: Int, sourceProfileId: Int, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            _isInitializingProfile.value = true
            try {
                profileConfigurationManager.initializeByCopying(targetProfileId, sourceProfileId)
                onComplete()
            } finally {
                _isInitializingProfile.value = false
            }
        }
    }

    fun deleteProfile(id: Int) {
        val avatarRef = _profiles.value.find { it.id == id }?.avatarRef
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            profileMutationCoordinator.serialized {
                dao.deleteProfileCascading(id)
                profileConfigurationManager.deleteProfileState(id)
                debridManager.clearForProfile(id)
                traktAuthManager.clearTokensForProfile(id)
                queueManager.clearForProfile(id)
            }
            deleteCustomAvatarFile(avatarRef)
        }
    }

    // Custom avatars uploaded via QR (AvatarUploadDialog) are stored as "custom:<absolute
    // path>" and otherwise live forever on disk — nothing else references or cleans them
    // up once a profile stops pointing at them.
    private fun deleteCustomAvatarFile(avatarRef: String?) {
        val path = avatarRef?.removePrefix("custom:")?.takeIf { avatarRef.startsWith("custom:") } ?: return
        runCatching { java.io.File(path).delete() }
    }

    fun goBackStep() {
        if (_wizardStep.value > 0) _wizardStep.value -= 1
    }
}
