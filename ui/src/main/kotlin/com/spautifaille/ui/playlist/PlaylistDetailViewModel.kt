package com.spautifaille.ui.playlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.library.PlaylistNameValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    /** `null` une fois chargé = playlist introuvable (supprimée). */
    val playlist: Playlist? = null,
    /** Ordre affiché (inclut le réordonnancement optimiste en cours). */
    val entries: List<PlaylistEntry> = emptyList(),
) {
    val isNotFound: Boolean get() = !isLoading && playlist == null
    val isSystem: Boolean get() = playlist?.isSystem == true
    val totalDurationMs: Long get() = entries.sumOf { it.track.durationMs ?: 0L }
}

/** Événements ponctuels (snackbar, navigation). */
sealed interface PlaylistDetailEvent {
    /** Titre retiré : proposer « Annuler » qui rappelle [PlaylistDetailViewModel.undoRemove]. */
    data class TrackRemoved(val track: Track, val position: Int) : PlaylistDetailEvent
    data class DownloadsQueued(val count: Int) : PlaylistDetailEvent
    data class PlayNextQueued(val track: Track) : PlaylistDetailEvent
    data class AddedToQueue(val track: Track) : PlaylistDetailEvent
    data object PlaylistDeleted : PlaylistDetailEvent
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackController: PlaybackController,
    private val notificationPermission: NotificationPermissionRequester,
) : ViewModel() {

    /** Argument de navigation `id` (Long). `-1` si absent → état « introuvable ». */
    val playlistId: Long = savedStateHandle.get<Long>(ARG_ID) ?: -1L

    /**
     * Réordonnancement optimiste. [baseIds] = ordre des `entryId` du repository au début du glissement :
     * l'override n'est affiché que tant que le repository n'a pas encore publié un ordre différent
     * (donc il disparaît de lui-même une fois le déplacement persisté, sans clignotement).
     */
    private data class Override(val baseIds: List<Long>, val entries: List<PlaylistEntry>)

    private val override = MutableStateFlow<Override?>(null)
    private var dragEntryId: Long? = null
    private var dragOrigin: Int = -1

    /** Dernier contenu publié par le repository (base de comparaison de l'override). */
    private var latestRemoteEntries: List<PlaylistEntry> = emptyList()
    private val latestRemoteIds: List<Long> get() = latestRemoteEntries.map { it.entryId }

    /** Liste actuellement affichée, calculée sans dépendre de la propagation du StateFlow. */
    private fun displayedEntries(): List<PlaylistEntry> {
        val ov = override.value
        return if (ov != null && ov.baseIds == latestRemoteIds) ov.entries else latestRemoteEntries
    }

    private val _events = Channel<PlaylistDetailEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistDetailEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        playlistRepository.observePlaylist(playlistId).onEach { loaded ->
            latestRemoteEntries = loaded?.entries.orEmpty()
        },
        override,
    ) { loaded, override ->
        if (loaded == null) {
            PlaylistDetailUiState(isLoading = false)
        } else {
            val remoteIds = loaded.entries.map { it.entryId }
            val shown = if (override != null && override.baseIds == remoteIds) override.entries else loaded.entries
            PlaylistDetailUiState(isLoading = false, playlist = loaded.playlist, entries = shown)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaylistDetailUiState())

    // region Lecture

    /** Joue la playlist à partir de l'index [startIndex] (ordre de la playlist). */
    fun playFrom(startIndex: Int) {
        val tracks = currentTracks()
        if (tracks.isEmpty()) return
        playbackController.play(tracks, startIndex.coerceIn(tracks.indices), false)
    }

    fun playAll(shuffle: Boolean) {
        val tracks = currentTracks()
        if (tracks.isEmpty()) return
        playbackController.play(tracks, 0, shuffle)
    }

    fun playNext(track: Track) {
        playbackController.playNext(listOf(track))
        _events.trySend(PlaylistDetailEvent.PlayNextQueued(track))
    }

    fun addToQueue(track: Track) {
        playbackController.addToQueue(listOf(track))
        _events.trySend(PlaylistDetailEvent.AddedToQueue(track))
    }

    fun downloadAll() {
        val tracks = currentTracks()
        if (tracks.isEmpty()) return
        notificationPermission.requestIfNeeded()
        viewModelScope.launch {
            downloadRepository.enqueue(tracks)
            _events.send(PlaylistDetailEvent.DownloadsQueued(tracks.size))
        }
    }

    private fun currentTracks(): List<Track> = uiState.value.entries.map { it.track }

    // endregion

    // region Gestion de la playlist

    /** Renomme la playlist (utilisateur). Renvoie `false` si le nom est invalide ou la playlist système. */
    fun rename(name: String): Boolean {
        if (uiState.value.isSystem || playlistId == Playlist.LIKED_ID) return false
        if (PlaylistNameValidator.validate(name) != null) return false
        val normalized = PlaylistNameValidator.normalize(name)
        viewModelScope.launch { playlistRepository.rename(playlistId, normalized) }
        return true
    }

    /** Supprime la playlist (utilisateur). Renvoie `false` pour une playlist système. */
    fun delete(): Boolean {
        if (uiState.value.isSystem || playlistId == Playlist.LIKED_ID) return false
        viewModelScope.launch {
            playlistRepository.delete(playlistId)
            _events.send(PlaylistDetailEvent.PlaylistDeleted)
        }
        return true
    }

    // endregion

    // region Réordonnancement / suppression

    /** Déplacement optimiste local pendant le glissement (indices dans la liste affichée). */
    fun onMove(from: Int, to: Int) {
        val current = displayedEntries()
        if (from !in current.indices || to !in current.indices || from == to) return
        if (dragEntryId == null) {
            dragEntryId = current[from].entryId
            dragOrigin = from
        }
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        override.value = Override(latestRemoteIds, reordered)
    }

    /** Fin du glissement : persiste le déplacement (origine → position finale). */
    fun onDragEnd() {
        val movedId = dragEntryId ?: return
        val origin = dragOrigin
        dragEntryId = null
        dragOrigin = -1
        val reordered = override.value?.entries ?: return
        val destination = reordered.indexOfFirst { it.entryId == movedId }
        if (destination < 0 || destination == origin) {
            override.value = null
            return
        }
        viewModelScope.launch {
            try {
                playlistRepository.moveEntry(playlistId, origin, destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                override.value = null
            }
        }
    }

    /** Retire une entrée et propose l'annulation via [PlaylistDetailEvent.TrackRemoved]. */
    fun removeEntry(entry: PlaylistEntry) {
        val position = displayedEntries().indexOfFirst { it.entryId == entry.entryId }.coerceAtLeast(0)
        viewModelScope.launch {
            playlistRepository.removeEntry(playlistId, entry.entryId)
            _events.send(PlaylistDetailEvent.TrackRemoved(entry.track, position))
        }
    }

    /** Annule un retrait : ré-ajoute le titre à la fin puis le replace à [position]. */
    fun undoRemove(track: Track, position: Int) {
        viewModelScope.launch {
            playlistRepository.addTracks(playlistId, listOf(track))
            val size = playlistRepository.observePlaylist(playlistId).first()?.entries?.size ?: return@launch
            val appendedAt = size - 1
            val target = position.coerceIn(0, appendedAt)
            if (appendedAt != target) playlistRepository.moveEntry(playlistId, appendedAt, target)
        }
    }

    // endregion

    companion object {
        /** Clé de l'argument de navigation (route `PlaylistRoute(id: Long)`). */
        const val ARG_ID = "id"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
