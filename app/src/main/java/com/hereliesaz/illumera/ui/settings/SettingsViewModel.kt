package com.hereliesaz.illumera.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.profile.ProfileMutationCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileMutationCoordinator: ProfileMutationCoordinator
) : ViewModel() {

    private fun updateProfile(
        profileId: Int,
        transform: (com.hereliesaz.illumera.data.model.ProfileEntity) -> com.hereliesaz.illumera.data.model.ProfileEntity
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            profileMutationCoordinator.update(profileId, transform)
        }
    }

    fun updateNavPosition(profileId: Int, position: String) {
        updateProfile(profileId) { it.copy(navPosition = position) }
    }

    fun updateRoundCorners(profileId: Int, roundCorners: Boolean) {
        updateProfile(profileId) { it.copy(roundCorners = roundCorners) }
    }

    fun updateHubRoundCorners(profileId: Int, hubRoundCorners: Boolean) {
        updateProfile(profileId) { it.copy(hubRoundCorners = hubRoundCorners) }
    }

    fun updateSplashEnabled(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(splashEnabled = enabled) }
    }

    fun updateContinueWatchingShape(profileId: Int, shape: String) {
        updateProfile(profileId) { it.copy(continueWatchingShape = shape) }
    }

    fun updateTunnelingEnabled(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(tunnelingEnabled = enabled) }
    }

    fun updateMapDV7ToHevc(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(mapDV7ToHevc = enabled) }
    }

    fun updateDecoderPriority(profileId: Int, priority: Int) {
        updateProfile(profileId) { it.copy(decoderPriority = priority) }
    }

    fun updateFrameRateMatching(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(frameRateMatching = enabled) }
    }

    fun updatePlayerPreference(profileId: Int, preference: String) {
        updateProfile(profileId) { it.copy(playerPreference = preference) }
    }

    fun updateAutoplayNextEpisode(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(autoplayNextEpisode = enabled) }
    }

    fun updateAutoSelectSource(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(autoSelectSource = enabled) }
    }

    fun updateRememberSourceSelection(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(rememberSourceSelection = enabled) }
    }

    fun updateSkipIntro(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(skipIntro = enabled) }
    }

    fun updateAutoplayThresholdMode(profileId: Int, mode: String) {
        updateProfile(profileId) { it.copy(autoplayThresholdMode = mode) }
    }

    fun updateAutoplayThresholdPercent(profileId: Int, percent: Int) {
        updateProfile(profileId) { it.copy(autoplayThresholdPercent = percent) }
    }

    fun updateAutoplayThresholdSeconds(profileId: Int, seconds: Int) {
        updateProfile(profileId) { it.copy(autoplayThresholdSeconds = seconds) }
    }

    fun updateWatchedThreshold(profileId: Int, percent: Int) {
        updateProfile(profileId) { it.copy(watchedThreshold = percent.coerceIn(50, 99)) }
    }

    fun updatePreferredAudioLanguage(profileId: Int, language: String) {
        updateProfile(profileId) { it.copy(preferredAudioLanguage = language) }
    }

    fun updatePreferredAudioLanguageSecondary(profileId: Int, language: String) {
        updateProfile(profileId) { it.copy(preferredAudioLanguageSecondary = language) }
    }

    fun updatePreferredSubtitleLanguage(profileId: Int, language: String) {
        updateProfile(profileId) { it.copy(preferredSubtitleLanguage = language) }
    }

    fun updatePreferredSubtitleLanguageSecondary(profileId: Int, language: String) {
        updateProfile(profileId) { it.copy(preferredSubtitleLanguageSecondary = language) }
    }

    fun updateSubtitleSize(profileId: Int, size: Int) {
        updateProfile(profileId) { it.copy(subtitleSize = size) }
    }

    fun updateSubtitleOffset(profileId: Int, offset: Int) {
        updateProfile(profileId) { it.copy(subtitleOffset = offset) }
    }

    fun updateSubtitleTextColor(profileId: Int, color: Long) {
        updateProfile(profileId) { it.copy(subtitleTextColor = color) }
    }

    fun updateSubtitleBackgroundColor(profileId: Int, color: Long) {
        updateProfile(profileId) { it.copy(subtitleBackgroundColor = color) }
    }

    fun updateAssRendererEnabled(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(assRendererEnabled = enabled) }
    }

    fun updateSourceSortingEnabled(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(sourceSortingEnabled = enabled) }
    }

    fun updateSourceEnabledQualities(profileId: Int, qualities: String) {
        updateProfile(profileId) { it.copy(sourceEnabledQualities = qualities) }
    }

    fun updateSourceExcludePhrases(profileId: Int, phrases: String) {
        updateProfile(profileId) { it.copy(sourceExcludePhrases = phrases) }
    }

    fun updateSourceSortPrimary(profileId: Int, sort: String) {
        updateProfile(profileId) { it.copy(sourceSortPrimary = sort) }
    }

    fun updateSourceMaxSizeGb(profileId: Int, sizeGb: Int) {
        updateProfile(profileId) { it.copy(sourceMaxSizeGb = sizeGb) }
    }

    fun updateSourceExcludedFormats(profileId: Int, formats: String) {
        updateProfile(profileId) { it.copy(sourceExcludedFormats = formats) }
    }

    fun updateSourceEpisodeTargetSizeMb(profileId: Int, sizeMb: Int) {
        updateProfile(profileId) { it.copy(sourceEpisodeTargetSizeMb = sizeMb) }
    }

    fun updateSourceMovieTargetSizeMb(profileId: Int, sizeMb: Int) {
        updateProfile(profileId) { it.copy(sourceMovieTargetSizeMb = sizeMb) }
    }

    fun updateSourceMinimumSeeds(profileId: Int, seeds: Int) {
        updateProfile(profileId) { it.copy(sourceMinimumSeeds = seeds) }
    }

    fun updateSourceSkipSeedless(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(sourceSkipSeedless = enabled) }
    }

    fun updateSourceAudioLanguageRequirement(profileId: Int, mode: String) {
        updateProfile(profileId) { it.copy(sourceAudioLanguageRequirement = mode) }
    }

    fun updateSourceSubtitleLanguageRequirement(profileId: Int, mode: String) {
        updateProfile(profileId) { it.copy(sourceSubtitleLanguageRequirement = mode) }
    }

    fun updateSourceAutoFallback(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(sourceAutoFallback = enabled) }
    }

    fun updateSourceDebridMaxWaitSeconds(profileId: Int, seconds: Int) {
        updateProfile(profileId) { it.copy(sourceDebridMaxWaitSeconds = seconds) }
    }

    fun updateMenuMoviesEnabled(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(menuMoviesEnabled = enabled) }
    }

    fun updateMenuSeriesEnabled(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(menuSeriesEnabled = enabled) }
    }

    fun updateMenuWatchlistEnabled(profileId: Int, enabled: Boolean) {
        updateProfile(profileId) { it.copy(menuWatchlistEnabled = enabled) }
    }
}
