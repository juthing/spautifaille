package com.spautifaille.ui.playlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.PlaylistWithTracks
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.library.PlaylistNameValidator
import com.spautifaille.ui.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /** Ids des titres dont le téléchargement est terminé. */
    val downloadedIds: Set<String> = emptySet(),
    /** Aucun réseau : seuls les titres téléchargés sont lisibles. */
    val isOffline: Boolean = false,
    /** Titre actuellement chargé dans le lecteur (mise en évidence dans la liste). */
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
) {
    val isNotFound: Boolean get() = !isLoading && playlist == null
    val isSystem: Boolean get() = playlist?.isSystem == true

    /** Playlist virtuelle « Téléchargés » : ni éditable ni réordonnable. */
    val isDownloadedPlaylist: Boolean get() = playlist?.id == Playlist.DOWNLOADED_ID
    val totalDurationMs: Long get() = entries.sumOf { it.track.durationMs ?: 0L }

    /** Entrées lisibles dans les conditions réseau actuelles (hors ligne : téléchargées uniquement). */
    val availableEntries: List<PlaylistEntry> get() = entries.availableEntries(downloadedIds, isOffline)
    val canPlay: Boolean get() = availableEntries.isNotEmpty()

    /** Tous les titres (au moins un) sont déjà téléchargés. */
    val isFullyDownloaded: Boolean
        get() = entries.isNotEmpty() && entries.all { it.track.id in downloadedIds }

    /** Le bouton « télécharger la playlist » a un sens (pas pour « Téléchargés », pas hors ligne). */
    val canDownload: Boolean get() = !isDownloadedPlaylist && !isOffline && entries.isNotEmpty() && !isFullyDownloaded

    fun isAvailable(entry: PlaylistEntry): Boolean = isTrackAvailable(entry.track.id, downloadedIds, isOffline)
}

/** Événements ponctuels (snackbar, navigation). */
sealed interface PlaylistDetailEvent {
    /** Titre retiré : proposer « Annuler » qui rappelle [PlaylistDetailViewModel.undoRemove]. */
    data class TrackRemoved(val track: Track, val position: Int) : PlaylistDetailEvent
    data class DownloadsQueued(val count: Int) : PlaylistDetailEvent
    data object PlaylistDeleted : PlaylistDetailEvent

    /** Appui sur un titre non téléchargé alors que l'appareil est hors ligne. */
    data object TrackUnavailableOffline : PlaylistDetailEvent
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackController: PlaybackController,
    private val notificationPermission: NotificationPermissionRequester,
    private val networkMonitor: NetworkMonitor,
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

    /** Données « vivantes » indépendantes du contenu de la playlist : téléchargements, réseau, lecture. */
    private data class Live(
        val downloadedIds: Set<String>,
        val isOffline: Boolean,
        val currentTrackId: String?,
        val isPlaying: Boolean,
    )

    private val completedDownloads = downloadRepository.observeDownloads()
        .map { it.completedDownloads() }
        .distinctUntilChanged()

    private val live: Flow<Live> = combine(
        completedDownloads,
        networkMonitor.isOnline,
        playbackController.state
            .map { it.currentTrack?.id to it.isPlaying }
            .distinctUntilChanged(),
    ) { downloads, isOnline, (currentId, playing) ->
        Live(
            downloadedIds = downloads.mapTo(HashSet()) { it.track.id },
            isOffline = !isOnline,
            currentTrackId = currentId,
            isPlaying = playing,
        )
    }

    /** Contenu de la playlist : base Room, ou téléchargements terminés pour la playlist virtuelle. */
    private val source: Flow<PlaylistWithTracks?> =
        if (playlistId == Playlist.DOWNLOADED_ID) {
            completedDownloads.map { downloads ->
                val entries = downloads.toPlaylistEntries()
                PlaylistWithTracks(downloadedPlaylist(entries.size), entries)
            }
        } else {
            playlistRepository.observePlaylist(playlistId)
        }

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        source.onEach { loaded -> latestRemoteEntries = loaded?.entries.orEmpty() },
        override,
        live,
    ) { loaded, override, live ->
        if (loaded == null) {
            PlaylistDetailUiState(isLoading = false)
        } else {
            val remoteIds = loaded.entries.map { it.entryId }
            val shown = if (override != null && override.baseIds == remoteIds) override.entries else loaded.entries
            PlaylistDetailUiState(
                isLoading = false,
                playlist = loaded.playlist,
                entries = shown,
                downloadedIds = live.downloadedIds,
                isOffline = live.isOffline,
                currentTrackId = live.currentTrackId,
                isPlaying = live.isPlaying,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaylistDetailUiState())

    // region Lecture

    /**
     * Joue la playlist à partir de l'entrée affichée à [startIndex]. Hors ligne, la file ne contient que les
     * titres téléchargés ; si l'entrée touchée n'en fait pas partie, rien n'est lu et
     * [PlaylistDetailEvent.TrackUnavailableOffline] est émis.
     */
    fun playFrom(startIndex: Int) {
        val state = uiState.value
        val tapped = state.entries.getOrNull(startIndex) ?: return
        val playable = state.availableEntries
        val index = playable.indexOfFirst { it.entryId == tapped.entryId }
        if (index < 0) {
            _events.trySend(PlaylistDetailEvent.TrackUnavailableOffline)
            return
        }
        playbackController.play(playable.map { it.track }, index, false)
    }

    /** Lit toute la playlist (hors ligne : ses titres téléchargés uniquement). */
    fun playAll(shuffle: Boolean) {
        val tracks = uiState.value.availableEntries.map { it.track }
        if (tracks.isEmpty()) return
        playbackController.play(tracks, 0, shuffle)
    }

    /** Télécharge les titres pas encore téléchargés. Sans effet hors ligne ou sur « Téléchargés ». */
    fun downloadAll() {
        val state = uiState.value
        if (state.isDownloadedPlaylist || state.isOffline) return
        val tracks = state.entries.map { it.track }.filterNot { it.id in state.downloadedIds }
        if (tracks.isEmpty()) return
        notificationPermission.requestIfNeeded()
        viewModelScope.launch {
            downloadRepository.enqueue(tracks)
            _events.send(PlaylistDetailEvent.DownloadsQueued(tracks.size))
        }
    }

    // endregion

    // region Gestion de la playlist

    /** Renomme la playlist (utilisateur). Renvoie `false` si le nom est invalide ou la playlist système. */
    fun rename(name: String): Boolean {
        if (isProtected()) return false
        if (PlaylistNameValidator.validate(name) != null) return false
        val normalized = PlaylistNameValidator.normalize(name)
        viewModelScope.launch { playlistRepository.rename(playlistId, normalized) }
        return true
    }

    /** Supprime la playlist (utilisateur). Renvoie `false` pour une playlist système. */
    fun delete(): Boolean {
        if (isProtected()) return false
        viewModelScope.launch {
            playlistRepository.delete(playlistId)
            _events.send(PlaylistDetailEvent.PlaylistDeleted)
        }
        return true
    }

    private fun isProtected(): Boolean =
        uiState.value.isSystem || playlistId == Playlist.LIKED_ID || playlistId == Playlist.DOWNLOADED_ID

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

    /**
     * Retire une entrée et propose l'annulation via [PlaylistDetailEvent.TrackRemoved]. Dans « Téléchargés »,
     * supprime le fichier téléchargé du titre (pas d'annulation possible).
     */
    fun removeEntry(entry: PlaylistEntry) {
        if (playlistId == Playlist.DOWNLOADED_ID) {
            viewModelScope.launch { downloadRepository.delete(entry.track.id) }
            return
        }
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
