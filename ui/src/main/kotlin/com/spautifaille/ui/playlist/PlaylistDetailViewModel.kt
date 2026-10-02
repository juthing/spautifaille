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
import com.spautifaille.domain.player.QueueSources
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.OfflineAvailability
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ce que fait le bouton principal selon que cette playlist est (ou non) celle en cours de lecture. */
enum class PlaylistPlayAction {
    /** La playlist n'est pas en cours de lecture : lancer depuis le début. */
    PLAY,

    /** Elle est en cours de lecture : mettre en pause. */
    PAUSE,

    /** Elle est la file en cours mais en pause : reprendre. */
    RESUME,
}

@Immutable
data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    /** `null` une fois chargé = playlist introuvable (supprimée). */
    val playlist: Playlist? = null,
    /** Ordre affiché (inclut le réordonnancement optimiste en cours). */
    val entries: List<PlaylistEntry> = emptyList(),
    /** Ids des titres dont le téléchargement est terminé. */
    val downloadedIds: Set<String> = emptySet(),
    /**
     * Ids des titres lisibles sans réseau : téléchargés + présents (même en partie) dans le cache de streaming.
     * Toujours un sur-ensemble de [downloadedIds].
     */
    val playableOfflineIds: Set<String> = emptySet(),
    /** Aucun réseau : seuls les titres de [playableOfflineIds] sont lisibles. */
    val isOffline: Boolean = false,
    /** Titre actuellement chargé dans le lecteur (mise en évidence dans la liste). */
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    /** La file du lecteur provient de CETTE playlist (`PlayerState.queueSourceId`). */
    val isThisPlaylistQueue: Boolean = false,
    /** Ids des titres aimés (action groupée « J'aime »). */
    val likedIds: Set<String> = emptySet(),
    /** `entryId` des lignes sélectionnées ; non vide = mode sélection. Toujours un sous-ensemble de [entries]. */
    val selectedEntryIds: Set<Long> = emptySet(),
) {
    val isNotFound: Boolean get() = !isLoading && playlist == null
    val isSystem: Boolean get() = playlist?.isSystem == true

    /** Playlist virtuelle « Téléchargés » : ni éditable ni réordonnable. */
    val isDownloadedPlaylist: Boolean get() = playlist?.id == Playlist.DOWNLOADED_ID
    val totalDurationMs: Long get() = entries.sumOf { it.track.durationMs ?: 0L }

    /** Entrées lisibles dans les conditions réseau actuelles (hors ligne : téléchargées ou en cache). */
    val availableEntries: List<PlaylistEntry> get() = entries.availableEntries(playableOfflineIds, isOffline)
    val canPlay: Boolean get() = availableEntries.isNotEmpty()

    /** Libellé/action du bouton principal : lecture, pause ou reprise de CETTE playlist. */
    val playAction: PlaylistPlayAction
        get() = when {
            !isThisPlaylistQueue -> PlaylistPlayAction.PLAY
            isPlaying -> PlaylistPlayAction.PAUSE
            else -> PlaylistPlayAction.RESUME
        }

    /** Tous les titres (au moins un) sont déjà téléchargés. */
    val isFullyDownloaded: Boolean
        get() = entries.isNotEmpty() && entries.all { it.track.id in downloadedIds }

    /** Le bouton « télécharger la playlist » a un sens (pas pour « Téléchargés », pas hors ligne). */
    val canDownload: Boolean get() = !isDownloadedPlaylist && !isOffline && entries.isNotEmpty() && !isFullyDownloaded

    fun isAvailable(entry: PlaylistEntry): Boolean = isTrackAvailable(entry.track.id, playableOfflineIds, isOffline)

    // region Sélection

    val isSelecting: Boolean get() = selectedEntryIds.isNotEmpty()

    /** Entrées sélectionnées, dans l'ordre de la liste. */
    val selectedEntries: List<PlaylistEntry> get() = entries.filter { it.entryId in selectedEntryIds }
    val selectedTracks: List<Track> get() = selectedEntries.map { it.track }
    val allSelected: Boolean get() = entries.isNotEmpty() && selectedEntryIds.size == entries.size

    /** Au moins un titre sélectionné est lisible (hors ligne : téléchargé ou en cache). */
    val canPlaySelection: Boolean get() = selectedEntries.any { isAvailable(it) }
    val canDownloadSelection: Boolean
        get() = !isDownloadedPlaylist && !isOffline && selectedEntries.any { it.track.id !in downloadedIds }

    /** Tous les titres sélectionnés sont déjà aimés : l'action devient « Je n'aime plus ». */
    val allSelectedLiked: Boolean get() = isSelecting && selectedEntries.all { it.track.id in likedIds }

    // endregion
}

/** Titre retiré d'une playlist, avec sa position d'origine (pour l'annulation). */
data class RemovedTrack(val track: Track, val position: Int)

/** Événements ponctuels (snackbar, navigation). */
sealed interface PlaylistDetailEvent {
    /** Titres retirés : proposer « Annuler » qui rappelle [PlaylistDetailViewModel.undoRemove]. */
    data class TracksRemoved(val items: List<RemovedTrack>) : PlaylistDetailEvent
    data class DownloadsQueued(val count: Int) : PlaylistDetailEvent
    data object PlaylistDeleted : PlaylistDetailEvent

    /** Appui sur un titre injouable alors que l'appareil est hors ligne (ni téléchargé ni en cache). */
    data object TrackUnavailableOffline : PlaylistDetailEvent

    /** J'aime / je n'aime plus groupé : [count] titres, [liked] = nouvel état. */
    data class TracksLiked(val count: Int, val liked: Boolean) : PlaylistDetailEvent
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val notificationPermission: NotificationPermissionRequester,
    private val networkMonitor: NetworkMonitor,
    private val offlineAvailability: OfflineAvailability,
    private val messenger: UiMessenger,
) : ViewModel() {

    /** Argument de navigation `id` (Long). `-1` si absent → état « introuvable ». */
    val playlistId: Long = savedStateHandle.get<Long>(ARG_ID) ?: -1L

    private val queueSourceId: String = QueueSources.playlist(playlistId)

    /**
     * Réordonnancement optimiste. [baseIds] = ordre des `entryId` du repository au début du glissement :
     * l'override n'est affiché que tant que le repository n'a pas encore publié un ordre différent
     * (donc il disparaît de lui-même une fois le déplacement persisté, sans clignotement).
     */
    private data class Override(val baseIds: List<Long>, val entries: List<PlaylistEntry>)

    private val override = MutableStateFlow<Override?>(null)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private var dragEntryId: Long? = null
    private var dragOrigin: Int = -1

    /** Ligne sur laquelle un appui long vient de commencer (sert à distinguer « sélection » de « réorganisation »). */
    private var longPressEntryId: Long? = null

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

    /** Données « vivantes » indépendantes du contenu de la playlist : téléchargements, réseau, lecture, likes. */
    private data class Live(
        val downloadedIds: Set<String>,
        val playableOfflineIds: Set<String>,
        val isOffline: Boolean,
        val currentTrackId: String?,
        val isPlaying: Boolean,
        val isThisPlaylistQueue: Boolean,
        val likedIds: Set<String>,
    )

    private data class PlayerSlice(
        val currentId: String?,
        val playing: Boolean,
        val fromThisPlaylist: Boolean,
    )

    private val completedDownloads = downloadRepository.observeDownloads()
        .map { it.completedDownloads() }
        .distinctUntilChanged()

    /**
     * Ces deux sources sont secondaires : si elles tardent ou échouent, la liste doit quand même s'afficher
     * (un `combine` attend toutes ses sources : une source muette bloquerait tout l'écran).
     */
    private val cachedPlayableIds: Flow<Set<String>> = offlineAvailability.observePlayableIds()
        .catch { emit(emptySet()) }
        .onStart { emit(emptySet()) }

    private val likedIds: Flow<Set<String>> = libraryRepository.observeLikedIds()
        .catch { emit(emptySet()) }
        .onStart { emit(emptySet()) }

    private val live: Flow<Live> = combine(
        completedDownloads,
        networkMonitor.isOnline,
        playbackController.state
            .map { PlayerSlice(it.currentTrack?.id, it.isPlaying || (it.playWhenReady && it.isBuffering), it.queueSourceId == queueSourceId) }
            .distinctUntilChanged(),
        cachedPlayableIds,
        likedIds,
    ) { downloads, isOnline, player, cached, liked ->
        val downloaded = downloads.mapTo(HashSet()) { it.track.id }
        Live(
            downloadedIds = downloaded,
            playableOfflineIds = downloaded + cached,
            isOffline = !isOnline,
            currentTrackId = player.currentId,
            isPlaying = player.playing,
            isThisPlaylistQueue = player.fromThisPlaylist,
            likedIds = liked,
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
        selection,
    ) { loaded, override, live, selected ->
        if (loaded == null) {
            PlaylistDetailUiState(isLoading = false)
        } else {
            val remoteIds = loaded.entries.map { it.entryId }
            val shown = if (override != null && override.baseIds == remoteIds) override.entries else loaded.entries
            val shownIds = shown.mapTo(HashSet()) { it.entryId }
            PlaylistDetailUiState(
                isLoading = false,
                playlist = loaded.playlist,
                entries = shown,
                downloadedIds = live.downloadedIds,
                playableOfflineIds = live.playableOfflineIds,
                isOffline = live.isOffline,
                currentTrackId = live.currentTrackId,
                isPlaying = live.isPlaying,
                isThisPlaylistQueue = live.isThisPlaylistQueue,
                likedIds = live.likedIds,
                // Une ligne disparue (retirée ailleurs) ne peut plus rester sélectionnée.
                selectedEntryIds = selected.filterTo(HashSet()) { it in shownIds },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaylistDetailUiState())

    // region Lecture

    /**
     * Joue la playlist à partir de l'entrée affichée à [startIndex]. Hors ligne, la file ne contient que les
     * titres lisibles (téléchargés ou en cache) ; si l'entrée touchée n'en fait pas partie, rien n'est lu et
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
        playbackController.play(playable.map { it.track }, index, false, queueSourceId)
    }

    /** Lit toute la playlist (hors ligne : ses titres lisibles uniquement). */
    fun playAll(shuffle: Boolean) {
        val tracks = uiState.value.availableEntries.map { it.track }
        if (tracks.isEmpty()) return
        playbackController.play(tracks, 0, shuffle, queueSourceId)
    }

    /**
     * Bouton principal : lance la playlist si ce n'est pas elle qui joue, sinon met en pause / reprend la lecture
     * en cours (sans relancer la file depuis le début).
     */
    fun onPlayButton() {
        when (uiState.value.playAction) {
            PlaylistPlayAction.PLAY -> playAll(shuffle = false)
            PlaylistPlayAction.PAUSE -> playbackController.pause()
            PlaylistPlayAction.RESUME -> playbackController.play()
        }
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

    // region Réordonnancement (appui long + glisser) / suppression

    /**
     * Début d'un appui long sur [entryId] (la poignée de glissement s'active après l'appui long). Si le doigt est
     * relâché sans que la ligne ait changé de place, c'est une sélection (voir [onDragEnd]).
     */
    fun onDragStart(entryId: Long) {
        longPressEntryId = entryId
    }

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

    /**
     * Fin du glissement : persiste le déplacement (origine → position finale), une seule fois au relâchement.
     * Sans aucun déplacement, l'appui long devient « entrer en mode sélection avec cette ligne ».
     */
    fun onDragEnd() {
        val pressed = longPressEntryId
        longPressEntryId = null
        val movedId = dragEntryId
        if (movedId == null) {
            if (pressed != null) startSelection(pressed)
            return
        }
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
     * Retire une entrée et propose l'annulation via [PlaylistDetailEvent.TracksRemoved]. Dans « Téléchargés »,
     * supprime le fichier téléchargé du titre (pas d'annulation possible).
     */
    fun removeEntry(entry: PlaylistEntry) = removeEntries(listOf(entry))

    private fun removeEntries(entries: List<PlaylistEntry>) {
        if (entries.isEmpty()) return
        if (playlistId == Playlist.DOWNLOADED_ID) {
            viewModelScope.launch { entries.forEach { downloadRepository.delete(it.track.id) } }
            return
        }
        val displayed = displayedEntries()
        val removed = entries
            .map { entry -> RemovedTrack(entry.track, displayed.indexOfFirst { it.entryId == entry.entryId }.coerceAtLeast(0)) }
            .sortedBy { it.position }
        viewModelScope.launch {
            entries.forEach { playlistRepository.removeEntry(playlistId, it.entryId) }
            _events.send(PlaylistDetailEvent.TracksRemoved(removed))
        }
    }

    /** Annule un retrait d'un seul titre (voir [undoRemove] pour plusieurs). */
    fun undoRemove(track: Track, position: Int) = undoRemove(listOf(RemovedTrack(track, position)))

    /**
     * Annule un retrait : ré-ajoute les titres à la fin puis replace chacun à sa position d'origine (dans l'ordre
     * croissant des positions, ce qui rétablit exactement l'ordre initial).
     */
    fun undoRemove(items: List<RemovedTrack>) {
        if (items.isEmpty()) return
        val sorted = items.sortedBy { it.position }
        viewModelScope.launch {
            playlistRepository.addTracks(playlistId, sorted.map { it.track })
            val size = playlistRepository.observePlaylist(playlistId).first()?.entries?.size ?: return@launch
            val firstAppended = size - sorted.size
            sorted.forEachIndexed { index, item ->
                val from = firstAppended + index
                val to = item.position.coerceIn(0, from)
                if (from != to) playlistRepository.moveEntry(playlistId, from, to)
            }
        }
    }

    // endregion

    // region Sélection multiple

    /** Entre en mode sélection avec cette ligne sélectionnée (sans effet si déjà en sélection : elle est basculée). */
    fun startSelection(entryId: Long) {
        if (uiState.value.entries.none { it.entryId == entryId }) return
        selection.value = selection.value + entryId
    }

    /** Ajoute ou retire une ligne ; quitter la sélection = désélectionner la dernière. */
    fun toggleSelection(entryId: Long) {
        if (uiState.value.entries.none { it.entryId == entryId }) return
        val current = selection.value
        selection.value = if (entryId in current) current - entryId else current + entryId
    }

    /** « Tout sélectionner » ; si tout l'est déjà, désélectionne tout (et quitte le mode). */
    fun toggleSelectAll() {
        val state = uiState.value
        selection.value = if (state.allSelected) emptySet() else state.entries.mapTo(HashSet()) { it.entryId }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** Lit la sélection (titres lisibles uniquement, dans l'ordre de la liste) comme nouvelle file. */
    fun playSelection() {
        val tracks = uiState.value.selectedEntries.filter { uiState.value.isAvailable(it) }.map { it.track }
        if (tracks.isEmpty()) return
        playbackController.play(tracks)
        clearSelection()
    }

    /** « Lire ensuite » pour la sélection. */
    fun playSelectionNext() {
        val tracks = playableSelection()
        if (tracks.isEmpty()) return
        playbackController.playNext(tracks)
        messenger.show(UiText.of(R.string.snack_play_next))
        clearSelection()
    }

    fun addSelectionToQueue() {
        val tracks = playableSelection()
        if (tracks.isEmpty()) return
        playbackController.addToQueue(tracks)
        messenger.show(UiText.of(R.string.snack_added_to_queue))
        clearSelection()
    }

    /** Télécharge les titres sélectionnés pas encore téléchargés. Sans effet hors ligne ou sur « Téléchargés ». */
    fun downloadSelection() {
        val state = uiState.value
        if (state.isDownloadedPlaylist || state.isOffline) return
        val tracks = state.selectedTracks.filterNot { it.id in state.downloadedIds }
        if (tracks.isEmpty()) {
            messenger.show(UiText.of(R.string.lib_downloads_nothing_to_do))
            clearSelection()
            return
        }
        notificationPermission.requestIfNeeded()
        clearSelection()
        viewModelScope.launch {
            downloadRepository.enqueue(tracks)
            _events.send(PlaylistDetailEvent.DownloadsQueued(tracks.size))
        }
    }

    /** Aime tous les titres sélectionnés ; s'ils le sont déjà tous, les « désaime ». */
    fun likeSelection() {
        val state = uiState.value
        val tracks = state.selectedTracks.distinctBy { it.id }
        if (tracks.isEmpty()) return
        val like = !state.allSelectedLiked
        clearSelection()
        viewModelScope.launch {
            tracks.forEach { libraryRepository.setLiked(it, like) }
            _events.send(PlaylistDetailEvent.TracksLiked(tracks.size, like))
        }
    }

    /** Retire la sélection de la playlist (avec annulation) ; dans « Téléchargés », supprime les fichiers. */
    fun removeSelection() {
        val entries = uiState.value.selectedEntries
        clearSelection()
        removeEntries(entries)
    }

    /** Titres sélectionnés à pousser dans la file : seuls les lisibles (hors ligne, pas de titre qui échouerait). */
    private fun playableSelection(): List<Track> {
        val state = uiState.value
        return state.selectedEntries.filter { state.isAvailable(it) }.map { it.track }
    }

    // endregion

    companion object {
        /** Clé de l'argument de navigation (route `PlaylistRoute(id: Long)`). */
        const val ARG_ID = "id"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
