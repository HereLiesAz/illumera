package com.hereliesaz.illumera.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.queue.QueueManager
import com.hereliesaz.illumera.data.trakt.TraktAuthManager
import com.hereliesaz.illumera.data.trakt.TraktSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val traktAuthManager: TraktAuthManager,
    private val traktSyncManager: TraktSyncManager,
    private val debridManager: DebridManager,
    private val queueManager: QueueManager
) : ViewModel() {

    private val _activeProfile = MutableStateFlow<ProfileEntity?>(null)
    val activeProfile: StateFlow<ProfileEntity?> = _activeProfile

    private var profileJob: Job? = null
    private var traktSyncJob: Job? = null
    private var traktConnectionWatchJob: Job? = null
    private var activeProfileId: Int? = null

    companion object {
        private const val TRAKT_POLL_INTERVAL_MS = 30_000L // 30 seconds
    }

    // Call this when user clicks a profile
    fun login(id: Int) {
        viewModelScope.launch {
            profileConfigurationManager.captureStartupRuntimeIfNeeded()

            val previousProfileId = activeProfileId
            if (previousProfileId != null && previousProfileId != id) {
                profileConfigurationManager.saveRuntimeState(previousProfileId)
            }

            profileConfigurationManager.loadRuntimeState(id)
            activeProfileId = id
            queueManager.reloadForActiveProfile()

            profileJob?.cancel()
            profileJob = launch {
                dao.getProfileFlow(id).collect { profile ->
                    _activeProfile.value = profile
                }
            }

            // Refresh Trakt connection for this profile and start periodic sync
            traktAuthManager.refreshConnectionState()
            traktSyncManager.resetActivityState()
            startTraktPeriodicSync()

            // Refresh Debrid connection for this profile
            debridManager.refreshConnectionState()

            // Watch for Trakt connection changes (e.g. user connects after login).
            // Cancel any collector from a previous login() call first — otherwise
            // switching profiles repeatedly leaks one live collector per switch,
            // each independently reacting to isConnected and causing duplicate syncs.
            traktConnectionWatchJob?.cancel()
            traktConnectionWatchJob = launch {
                traktAuthManager.isConnected.collect { connected ->
                    if (connected && traktSyncJob?.isActive != true) {
                        startTraktPeriodicSync()
                    } else if (!connected) {
                        traktSyncJob?.cancel()
                    }
                }
            }
        }
    }

    private fun startTraktPeriodicSync() {
        traktSyncJob?.cancel()
        if (!traktAuthManager.isConnected.value) return

        traktSyncJob = viewModelScope.launch(Dispatchers.IO) {
            // Immediate full sync on login
            traktSyncManager.syncWatchlist()
            traktSyncManager.syncPlaybackProgress()
            traktSyncManager.syncSeriesNextUp()

            // Then lightweight activity check every 30 seconds —
            // only triggers a full sync when Trakt detects changes
            while (isActive) {
                delay(TRAKT_POLL_INTERVAL_MS)
                traktSyncManager.checkAndSync()
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            activeProfileId?.let { profileConfigurationManager.saveRuntimeState(it) }
            profileConfigurationManager.clearLastActiveProfileId()
            activeProfileId = null
            queueManager.reloadForActiveProfile()
            profileJob?.cancel()
            traktSyncJob?.cancel()
            traktConnectionWatchJob?.cancel()
            _activeProfile.value = null
        }
    }

    suspend fun persistActiveProfileState() {
        activeProfileId?.let { profileConfigurationManager.saveRuntimeState(it) }
    }
}
