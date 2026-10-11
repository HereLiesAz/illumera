package com.hereliesaz.illumera.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.debrid.DebridManager
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DebridLibraryEvent {
    data class StreamResolved(val id: String, val url: String, val name: String) : DebridLibraryEvent()
    data class Error(val message: String) : DebridLibraryEvent()
}

data class DebridLibraryUiState(
    val items: List<DebridItem> = emptyList(),
    val isLoading: Boolean = false,
    val resolvingItemId: String? = null
)

@HiltViewModel
class DebridLibraryViewModel @Inject constructor(
    private val debridManager: DebridManager
) : ViewModel() {

    val connectedProvider: StateFlow<DebridProvider?> = debridManager.connectedProvider

    private val _uiState = MutableStateFlow(DebridLibraryUiState())
    val uiState: StateFlow<DebridLibraryUiState> = _uiState

    private val _events = Channel<DebridLibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            debridManager.connectedProvider.collect { provider ->
                if (provider != null) refresh() else _uiState.value = DebridLibraryUiState()
            }
        }
    }

    private var refreshJob: Job? = null
    private var playJob: Job? = null

    fun refresh() {
        // A newer refresh supersedes an in-flight one; otherwise a slow older listing could
        // land last and overwrite fresher items.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = debridManager.listLibrary()) {
                is DebridResult.Success -> _uiState.value = _uiState.value.copy(
                    items = result.value,
                    isLoading = false
                )
                is DebridResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.send(DebridLibraryEvent.Error(result.message))
                }
            }
        }
    }

    fun play(item: DebridItem) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingItemId = item.id)
            when (val result = debridManager.getStreamUrl(item)) {
                is DebridResult.Success -> {
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                    _events.send(DebridLibraryEvent.StreamResolved(item.id, result.value, item.name))
                }
                is DebridResult.Failure -> {
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                    _events.send(DebridLibraryEvent.Error(result.message))
                }
            }
        }
    }

    /**
     * Called when the Watchlist screen leaves composition. A stream that resolves while the
     * screen is gone must not start playback the next time the user comes back, so the
     * in-flight resolve is cancelled and any undelivered one-shot events are dropped.
     */
    fun onScreenDisposed() {
        playJob?.cancel()
        playJob = null
        _uiState.value = _uiState.value.copy(resolvingItemId = null)
        while (_events.tryReceive().isSuccess) { /* drop stale events */ }
    }

    fun delete(item: DebridItem) {
        viewModelScope.launch {
            when (val result = debridManager.deleteItem(item)) {
                is DebridResult.Success -> refresh()
                is DebridResult.Failure -> _events.send(DebridLibraryEvent.Error(result.message))
            }
        }
    }
}
