package com.spautifaille.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class LibraryUiState(
    val isLoading: Boolean = true,
    /** « Titres likés » (système) en premier, puis les playlists utilisateur dans l'ordre du repository. */
    val playlists: List<Playlist> = emptyList(),
    /** Téléchargements terminés uniquement. */
    val downloads: List<Download> = emptyList(),
    val storage: StorageUsage? = null,
    /** Du plus récent au plus ancien. */
    val history: List<HistoryEntry> = emptyList(),
    val subscriptions: List<Artist> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = combine(
        playlistRepository.observePlaylists(),
        downloadRepository.observeDownloads(),
        downloadRepository.observeStorageUsage(),
        libraryRepository.observeHistory(),
        libraryRepository.observeSubscriptions(),
    ) { playlists, downloads, storage, history, subscriptions ->
        LibraryUiState(
            isLoading = false,
            playlists = playlists.sortedByDescending { it.isSystem },
            downloads = downloads.filter { it.state == DownloadState.COMPLETED },
            storage = storage,
            history = history,
            subscriptions = subscriptions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    // region Playlists

    /** Crée une playlist. Renvoie `false` (sans effet) si le nom est invalide. */
    fun createPlaylist(name: String): Boolean {
        if (PlaylistNameValidator.validate(name) != null) return false
        val normalized = PlaylistNameValidator.normalize(name)
        viewModelScope.launch { playlistRepository.create(normalized) }
        return true
    }

    /** Renomme une playlist utilisateur. Sans effet sur une playlist système ou si le nom est invalide. */
    fun renamePlaylist(id: Long, name: String): Boolean {
        if (isProtected(id) || PlaylistNameValidator.validate(name) != null) return false
        val normalized = PlaylistNameValidator.normalize(name)
        viewModelScope.launch { playlistRepository.rename(id, normalized) }
        return true
    }

    /** Supprime une playlist utilisateur. Sans effet sur une playlist système. */
    fun deletePlaylist(id: Long): Boolean {
        if (isProtected(id)) return false
        viewModelScope.launch { playlistRepository.delete(id) }
        return true
    }

    fun playPlaylist(id: Long, shuffle: Boolean = false) {
        viewModelScope.launch {
            val tracks = playlistRepository.observePlaylist(id).first()?.entries?.map { it.track }.orEmpty()
            if (tracks.isNotEmpty()) playbackController.play(tracks, 0, shuffle)
        }
    }

    private fun isProtected(id: Long): Boolean =
        id == Playlist.LIKED_ID || uiState.value.playlists.any { it.id == id && it.isSystem }

    // endregion

    // region Téléchargés

    fun playDownloads(startIndex: Int = 0, shuffle: Boolean = false) {
        val tracks = uiState.value.downloads.map { it.track }
        if (tracks.isNotEmpty()) playbackController.play(tracks, startIndex.coerceIn(tracks.indices), shuffle)
    }

    // endregion

    // region Historique

    /** Lit le titre à [index] puis les suivants de l'historique (plus anciens). */
    fun playHistory(index: Int) {
        val tracks = uiState.value.history.drop(index).map { it.track }
        if (tracks.isNotEmpty()) playbackController.play(tracks, 0, false)
    }

    fun removeHistoryEntry(id: Long) {
        viewModelScope.launch { libraryRepository.removeHistoryEntry(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { libraryRepository.clearHistory() }
    }

    // endregion

    // region Artistes

    fun unsubscribe(artistUrl: String) {
        viewModelScope.launch { libraryRepository.unsubscribe(artistUrl) }
    }

    // endregion

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
