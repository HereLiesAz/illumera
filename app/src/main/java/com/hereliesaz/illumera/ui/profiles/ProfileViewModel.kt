package com.hereliesaz.illumera.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.StremioAuthError
import com.hereliesaz.illumera.data.trakt.TraktAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val debridManager: DebridManager,
    private val traktAuthManager: TraktAuthManager
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProfileEntity>>(emptyList())
    val profiles: StateFlow<List<ProfileEntity>> = _profiles

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _wizardStep = MutableStateFlow(0)
    val wizardStep: StateFlow<Int> = _wizardStep

    private val _isInitializingProfile = MutableStateFlow(false)
    val isInitializingProfile: StateFlow<Boolean> = _isInitializingProfile

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
            if (editingProfileId != null) {
                val previous = _profiles.value.find { it.id == editingProfileId }
                val updatedProfile = previous?.copy(
                    name = tempName,
                    avatarRef = tempAvatarRef,
                    themeId = tempThemeId
                )
                if (updatedProfile != null) dao.updateProfile(updatedProfile)
                if (previous != null && previous.avatarRef != tempAvatarRef) {
                    deleteCustomAvatarFile(previous.avatarRef)
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
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            _isInitializingProfile.value = true
            try {
                val result = profileConfigurationManager.initializeFromScratchWithStremio(profileId, email, password)
                result.fold(
                    onSuccess = { onResult(true, null) },
                    onFailure = { error ->
                        val message = when (error) {
                            is StremioAuthError.InvalidCredentials -> "Invalid email or password."
                            is StremioAuthError.NetworkError -> "Network error. Check your connection and try again."
                            else -> error.message ?: "Couldn't connect to Stremio."
                        }
                        onResult(false, message)
                    }
                )
            } finally {
                _isInitializingProfile.value = false
            }
        }
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
            dao.deleteProfileCascading(id)
            profileConfigurationManager.deleteProfileState(id)
            debridManager.clearForProfile(id)
            traktAuthManager.clearTokensForProfile(id)
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
