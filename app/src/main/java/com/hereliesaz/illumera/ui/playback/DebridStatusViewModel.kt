package com.hereliesaz.illumera.ui.playback

import androidx.lifecycle.ViewModel
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.model.debrid.DebridSourceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Lets a source list (the player's, or a Details page's) show which torrent sources are being
 * added to a debrid service or are already in one. Keys are lowercase info hashes.
 */
@HiltViewModel
class DebridStatusViewModel @Inject constructor(
    private val debridManager: DebridManager
) : ViewModel() {
    val statuses: StateFlow<Map<String, DebridSourceStatus>> = debridManager.sourceStatuses

    suspend fun refresh() = debridManager.refreshSourceStatuses()
}
