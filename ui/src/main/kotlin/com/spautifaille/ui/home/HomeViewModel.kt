package com.spautifaille.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime
import javax.inject.Inject

enum class Greeting { MORNING, AFTERNOON, EVENING }

data class HomeUiState(
    val greeting: Greeting = Greeting.MORNING,
    /** Titres récemment écoutés, sans doublon, du plus récent au plus ancien. */
    val recent: List<Track> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = libraryRepository.observeHistory(HISTORY_LIMIT)
        .map { history ->
            HomeUiState(
                greeting = greetingFor(LocalTime.now().hour),
                recent = history.map { it.track }.distinctBy { it.id }.take(RECENT_COUNT),
                isLoading = false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Lit le titre choisi et place à sa suite les autres titres récents. */
    fun onRecentClick(track: Track) {
        val recent = uiState.value.recent
        val index = recent.indexOfFirst { it.id == track.id }
        if (index >= 0) playbackController.play(recent, startIndex = index) else playbackController.play(listOf(track))
    }

    companion object {
        const val HISTORY_LIMIT = 20
        const val RECENT_COUNT = 10

        fun greetingFor(hour: Int): Greeting = when (hour) {
            in 5..11 -> Greeting.MORNING
            in 12..17 -> Greeting.AFTERNOON
            else -> Greeting.EVENING
        }
    }
}
