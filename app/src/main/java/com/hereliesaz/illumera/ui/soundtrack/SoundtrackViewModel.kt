package com.hereliesaz.illumera.ui.soundtrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.soundtrack.Soundtrack
import com.hereliesaz.illumera.data.soundtrack.SoundtrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SoundtrackViewModel @Inject constructor(
    private val repository: SoundtrackRepository
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Empty : State
        data class Loaded(val soundtrack: Soundtrack) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun load(type: String, imdbId: String, season: Int? = null, episode: Int? = null) {
        job?.cancel()
        _state.value = State.Loading
        job = viewModelScope.launch {
            _state.value = try {
                repository.load(type, imdbId, season, episode)?.let { State.Loaded(it) } ?: State.Empty
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.w("Soundtrack", "Soundtrack lookup failed", e)
                State.Empty
            }
        }
    }
}
