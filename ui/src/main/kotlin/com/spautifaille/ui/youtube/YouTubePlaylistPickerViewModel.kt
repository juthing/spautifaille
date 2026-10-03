package com.spautifaille.ui.youtube

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.youtube.RemoteLibraryPlaylist
import com.spautifaille.domain.youtube.YouTubePlaylistSync
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toAppError
import com.spautifaille.ui.common.toMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class YouTubePlaylistPickerUiState(
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val playlists: List<RemoteLibraryPlaylist> = emptyList(),
    /** Identifiants YouTube cochés (jamais une playlist déjà liée). */
    val selectedIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
) {
    val selectableCount: Int get() = playlists.count { !it.isLinked }
    val canImport: Boolean get() = selectedIds.isNotEmpty() && !isImporting
    val allSelected: Boolean get() = selectableCount > 0 && selectedIds.size == selectableCount
}

sealed interface YouTubePlaylistPickerEvent {
    /** Import terminé : l'écran se ferme (le message est publié par le ViewModel). */
    data class Imported(val playlistIds: List<Long>) : YouTubePlaylistPickerEvent
}

/** Choix des playlists du compte à importer comme playlists liées (3ᵉ option de la création de playlist). */
@HiltViewModel
class YouTubePlaylistPickerViewModel @Inject constructor(
    private val playlistSync: YouTubePlaylistSync,
    private val messenger: UiMessenger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubePlaylistPickerUiState())
    val uiState: StateFlow<YouTubePlaylistPickerUiState> = _uiState.asStateFlow()

    private val _events = Channel<YouTubePlaylistPickerEvent>(Channel.BUFFERED)
    val events: Flow<YouTubePlaylistPickerEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val playlists = playlistSync.listRemotePlaylists()
                _uiState.update { it.copy(isLoading = false, playlists = playlists, selectedIds = emptySet()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.toAppError()) }
            }
        }
    }

    fun toggle(id: String) {
        _uiState.update { state ->
            val playlist = state.playlists.firstOrNull { it.id == id } ?: return@update state
            if (playlist.isLinked || state.isImporting) return@update state
            state.copy(selectedIds = if (id in state.selectedIds) state.selectedIds - id else state.selectedIds + id)
        }
    }

    fun toggleAll() {
        _uiState.update { state ->
            if (state.isImporting) return@update state
            val all = state.playlists.filterNot { it.isLinked }.map { it.id }.toSet()
            state.copy(selectedIds = if (state.allSelected) emptySet() else all)
        }
    }

    fun import() {
        val state = _uiState.value
        if (!state.canImport) return
        // Ordre d'affichage conservé, quel que soit l'ordre dans lequel les cases ont été cochées.
        val ids = state.playlists.filter { it.id in state.selectedIds }.map { it.id }
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            try {
                val localIds = playlistSync.importPlaylists(ids)
                messenger.show(UiText.Resource(R.string.yt_import_done, listOf(localIds.size)))
                _uiState.update { it.copy(isImporting = false) }
                _events.send(YouTubePlaylistPickerEvent.Imported(localIds))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La liste reste affichée et la sélection conservée : l'utilisateur peut réessayer.
                _uiState.update { it.copy(isImporting = false) }
                messenger.show(UiText.of(e.toAppError().toMessage()))
            }
        }
    }
}
