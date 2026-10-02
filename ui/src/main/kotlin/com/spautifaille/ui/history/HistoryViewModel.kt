package com.spautifaille.ui.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.OfflineAvailability
import com.spautifaille.ui.network.NetworkMonitor
import com.spautifaille.ui.playlist.availableTracks
import com.spautifaille.ui.playlist.isTrackAvailable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
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
    /** Aucun réseau : seuls les titres de [playableOfflineIds] sont lisibles (les autres sont grisés). */
    val isOffline: Boolean = false,
    /** Titres lisibles sans réseau : téléchargés ou présents dans le cache de streaming. */
    val playableOfflineIds: Set<String> = emptySet(),
) {
    fun isAvailable(trackId: String): Boolean = isTrackAvailable(trackId, playableOfflineIds, isOffline)
}

/** Historique d'écoute complet : lecture à partir d'une entrée, retrait d'une entrée, effacement total. */
@HiltViewModel
class HistoryViewModel internal constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val networkMonitor: NetworkMonitor,
    private val offlineAvailability: OfflineAvailability,
    private val today: () -> LocalDate,
    private val zone: () -> ZoneId,
) : ViewModel() {

    @Inject
    constructor(
        libraryRepository: LibraryRepository,
        playbackController: PlaybackController,
        networkMonitor: NetworkMonitor,
        offlineAvailability: OfflineAvailability,
    ) : this(libraryRepository, playbackController, networkMonitor, offlineAvailability, LocalDate::now, ZoneId::systemDefault)

    val uiState: StateFlow<HistoryUiState> = combine(
        libraryRepository.observeHistory(HISTORY_LIMIT),
        playbackController.state,
        networkMonitor.isOnline,
        // Source secondaire : si elle tarde ou échoue, l'historique doit quand même s'afficher.
        offlineAvailability.observePlayableIds().catch { emit(emptySet()) }.onStart { emit(emptySet()) },
    ) { history, player, isOnline, playable ->
        HistoryUiState(
            isLoading = false,
            entries = history,
            items = groupHistoryByDay(history, today(), zone()),
            currentTrackId = player.currentTrack?.id,
            isPlaying = player.isPlaying,
            isOffline = !isOnline,
            playableOfflineIds = playable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    /**
     * Lit le titre à [index] puis les suivants de l'historique (plus anciens). Hors ligne : seuls les titres
     * lisibles (téléchargés ou en cache) entrent dans la file, et rien ne démarre si le titre touché n'en fait pas partie.
     */
    fun playFrom(index: Int) {
        val state = uiState.value
        val tapped = state.entries.getOrNull(index) ?: return
        if (!state.isAvailable(tapped.track.id)) return
        val tracks = state.entries.drop(index).map { it.track }.availableTracks(state.playableOfflineIds, state.isOffline)
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
