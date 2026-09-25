package com.hereliesaz.illumera.ui.soundtrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.soundtrack.Soundtrack
import com.hereliesaz.illumera.data.soundtrack.SoundtrackGroup
import com.hereliesaz.illumera.data.soundtrack.SoundtrackRepository
import com.hereliesaz.illumera.data.soundtrack.Tunefind
import com.hereliesaz.illumera.data.soundtrack.TunefindSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * IMDb songs (via the Soundtrack addon) show first; for a movie or a single episode,
 * Tunefind is then read on the device and merged in, taking over the order when found.
 */
@HiltViewModel
class SoundtrackViewModel @Inject constructor(
    private val repository: SoundtrackRepository,
    private val tunefind: TunefindSource,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Empty : State

        /** [checking]: Tunefind still loading. [fromTunefind]: its songs are merged in. */
        data class Loaded(
            val soundtrack: Soundtrack,
            val checking: Boolean = false,
            val fromTunefind: Boolean = false,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun load(type: String, imdbId: String, season: Int? = null, episode: Int? = null) {
        job?.cancel()
        _state.value = State.Loading
        job = viewModelScope.launch {
            val imdb = attempt("Soundtrack lookup failed") { repository.load(type, imdbId, season, episode) }
            val title = imdb?.title
            val single = type == "movie" || (season != null && episode != null)
            val canCheck = single && !title.isNullOrBlank()
            _state.value = when {
                imdb != null && imdb.groups.isNotEmpty() -> State.Loaded(imdb, checking = canCheck)
                canCheck -> State.Loading
                else -> State.Empty
            }
            if (!canCheck) return@launch

            val songs = attempt("Tunefind lookup failed") { tunefind.load(type, title!!, season, episode) }
            _state.value = when {
                !songs.isNullOrEmpty() -> {
                    val base = imdb?.groups?.firstOrNull() ?: SoundtrackGroup(season, episode)
                    val merged = base.copy(songs = Tunefind.merge(songs, base.songs))
                    State.Loaded(imdb!!.copy(groups = listOf(merged)), fromTunefind = true)
                }
                imdb != null && imdb.groups.isNotEmpty() -> State.Loaded(imdb)
                else -> State.Empty
            }
        }
    }

    private suspend fun <T> attempt(message: String, block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        com.hereliesaz.illumera.crash.AppErrors.w("Soundtrack", message, e)
        null
    }
}
