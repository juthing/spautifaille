package com.spautifaille.ui.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class HistoryUiState(
    val isLoading: Boolean = true,
    /** Du plus récent au plus ancien (ordre de lecture « à partir d'ici »). */
    val entries: List<HistoryEntry> = emptyList(),
    /** Entrées regroupées par jour, avec leurs en-têtes. */
    val items: List<HistoryListItem> = emptyList(),
    /** Titre en cours dans le lecteur, mis en évidence dans la liste. */
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
)

/** Historique d'écoute complet : lecture à partir d'une entrée, retrait d'une entrée, effacement total. */
@HiltViewModel
class HistoryViewModel internal constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val today: () -> LocalDate,
    private val zone: () -> ZoneId,
) : ViewModel() {

    @Inject
    constructor(
        libraryRepository: LibraryRepository,
        playbackController: PlaybackController,
    ) : this(libraryRepository, playbackController, LocalDate::now, ZoneId::systemDefault)

    val uiState: StateFlow<HistoryUiState> = combine(
        libraryRepository.observeHistory(HISTORY_LIMIT),
        playbackController.state,
    ) { history, player ->
        HistoryUiState(
            isLoading = false,
            entries = history,
            items = groupHistoryByDay(history, today(), zone()),
            currentTrackId = player.currentTrack?.id,
            isPlaying = player.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    /** Lit le titre à [index] puis les suivants de l'historique (plus anciens). */
    fun playFrom(index: Int) {
        val tracks = uiState.value.entries.drop(index).map { it.track }
        if (tracks.isNotEmpty()) playbackController.play(tracks, 0, false)
    }

    fun remove(entryId: Long) {
        viewModelScope.launch { libraryRepository.removeHistoryEntry(entryId) }
    }

    fun clear() {
        viewModelScope.launch { libraryRepository.clearHistory() }
    }

    companion object {
        const val HISTORY_LIMIT = 500
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
