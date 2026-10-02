package com.spautifaille.ui.remoteplaylist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.common.toAppError
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RemotePlaylistStatus {
    data object Loading : RemotePlaylistStatus
    data class Error(val error: AppError) : RemotePlaylistStatus
    data object Content : RemotePlaylistStatus
}

@Immutable
data class RemotePlaylistUiState(
    val status: RemotePlaylistStatus = RemotePlaylistStatus.Loading,
    val playlist: RemotePlaylist? = null,
    /** Titres chargés jusqu'ici (pages successives). */
    val tracks: List<Track> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Échec du chargement de la page suivante (affiche « Réessayer » en pied de liste). */
    val loadMoreError: AppError? = null,
    val isSaving: Boolean = false,
    /** Nombre de titres chargés pendant l'enregistrement (progression). */
    val saveProgress: Int = 0,
    /** Nombre de titres visé pendant l'enregistrement (`null` = inconnu → progression indéterminée). */
    val saveTarget: Int? = null,
)

sealed interface RemotePlaylistEvent {
    data class Saved(val playlistId: Long, val name: String, val trackCount: Int) : RemotePlaylistEvent
    data class SaveFailed(val error: AppError) : RemotePlaylistEvent
}

@HiltViewModel
class RemotePlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val streamRepository: StreamRepository,
    private val playlistRepository: PlaylistRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    /** Argument de navigation `url` (String). */
    private val url: String = savedStateHandle.get<String>(ARG_URL).orEmpty()

    private val _uiState = MutableStateFlow(RemotePlaylistUiState())
    val uiState: StateFlow<RemotePlaylistUiState> = _uiState.asStateFlow()

    private val _events = Channel<RemotePlaylistEvent>(Channel.BUFFERED)
    val events: Flow<RemotePlaylistEvent> = _events.receiveAsFlow()

    private var nextPage: PageToken? = null
    private val pageMutex = Mutex()

    init {
        load()
    }

    /** (Re)charge la première page. */
    fun load() {
        _uiState.value = RemotePlaylistUiState(status = RemotePlaylistStatus.Loading)
        nextPage = null
        viewModelScope.launch {
            try {
                val page = streamRepository.remotePlaylist(url)
                nextPage = page.tracks.next
                _uiState.value = RemotePlaylistUiState(
                    status = RemotePlaylistStatus.Content,
                    playlist = page.playlist,
                    tracks = page.tracks.items,
                    hasMore = page.tracks.hasMore,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = RemotePlaylistUiState(status = RemotePlaylistStatus.Error(e.toAppError()))
            }
        }
    }

    /** Charge la page suivante (appelé quand la liste approche de sa fin, ou pour « Réessayer »). */
    fun loadMore() {
        val current = _uiState.value
        if (current.status != RemotePlaylistStatus.Content || !current.hasMore || current.isLoadingMore || current.isSaving) {
            return
        }
        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        viewModelScope.launch {
            val error = fetchNextPage()
            _uiState.update { it.copy(isLoadingMore = false, loadMoreError = error) }
        }
    }

    /** Charge une page ; renvoie l'erreur éventuelle. Sérialisé pour ne jamais lire deux fois le même jeton. */
    private suspend fun fetchNextPage(): AppError? = pageMutex.withLock {
        val token = nextPage ?: return@withLock null
        try {
            val page = streamRepository.remotePlaylist(url, token)
            nextPage = page.tracks.next
            _uiState.update {
                it.copy(
                    tracks = it.tracks + page.tracks.items,
                    hasMore = page.tracks.hasMore,
                    saveProgress = if (it.isSaving) it.tracks.size + page.tracks.items.size else it.saveProgress,
                )
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.toAppError()
        }
    }

    // region Lecture

    fun playFrom(index: Int) {
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        playbackController.play(tracks, index.coerceIn(tracks.indices), false)
    }

    fun playAll(shuffle: Boolean) {
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        playbackController.play(tracks, 0, shuffle)
    }

    // endregion

    /**
     * Enregistre la playlist dans la bibliothèque : charge d'abord toutes les pages restantes
     * (plafonné à [MAX_SAVED_TRACKS] titres), puis crée une playlist locale.
     */
    fun saveToLibrary() {
        val current = _uiState.value
        val playlist = current.playlist
        if (current.status != RemotePlaylistStatus.Content || playlist == null || current.isSaving) return
        val target = playlist.trackCount?.toInt()?.coerceIn(1, MAX_SAVED_TRACKS)
        _uiState.update {
            it.copy(isSaving = true, saveProgress = it.tracks.size, saveTarget = target, loadMoreError = null)
        }
        viewModelScope.launch {
            var error: AppError? = null
            while (error == null && nextPage != null && _uiState.value.tracks.size < MAX_SAVED_TRACKS) {
                error = fetchNextPage()
            }
            if (error != null) {
                _uiState.update { it.copy(isSaving = false) }
                _events.send(RemotePlaylistEvent.SaveFailed(error))
                return@launch
            }
            val tracks = _uiState.value.tracks.take(MAX_SAVED_TRACKS)
            try {
                val id = playlistRepository.create(playlist.name, tracks)
                _events.send(RemotePlaylistEvent.Saved(id, playlist.name, tracks.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(RemotePlaylistEvent.SaveFailed(e.toAppError()))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    companion object {
        /** Clé de l'argument de navigation (route `RemotePlaylistRoute(url: String)`). */
        const val ARG_URL = "url"

        /** Plafond de titres importés dans la bibliothèque. */
        const val MAX_SAVED_TRACKS = 1000
    }
}
