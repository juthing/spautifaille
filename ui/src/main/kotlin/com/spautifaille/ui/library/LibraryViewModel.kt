package com.spautifaille.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.QueueSources
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.OfflineAvailability
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.network.NetworkMonitor
import com.spautifaille.ui.playlist.availableTracks
import com.spautifaille.ui.playlist.completedDownloads
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class LibraryUiState(
    val isLoading: Boolean = true,
    /**
     * « Titres likés » et « Téléchargés » (virtuelle) épinglées en tête (« Téléchargés » d'abord hors ligne),
     * puis les playlists utilisateur dans l'ordre du repository.
     */
    val playlists: List<Playlist> = emptyList(),
    /** Aucun réseau : seuls les titres téléchargés sont disponibles. */
    val isOffline: Boolean = false,
) {
    /** Au moins une playlist créée par l'utilisateur (sinon : état vide). */
    val hasUserPlaylists: Boolean get() = playlists.any { !it.isPinned }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackController: PlaybackController,
    private val networkMonitor: NetworkMonitor,
    private val offlineAvailability: OfflineAvailability,
    private val notificationPermission: NotificationPermissionRequester,
    private val messenger: UiMessenger,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = combine(
        playlistRepository.observePlaylists(),
        downloadRepository.observeDownloads().map { it.completedDownloads().size }.distinctUntilChanged(),
        networkMonitor.isOnline,
    ) { playlists, downloadedCount, isOnline ->
        LibraryUiState(
            isLoading = false,
            playlists = orderLibraryPlaylists(playlists, downloadedCount, isOffline = !isOnline),
            isOffline = !isOnline,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    /** Crée une playlist. Renvoie `false` (sans effet) si le nom est invalide. */
    fun createPlaylist(name: String): Boolean {
        if (PlaylistNameValidator.validate(name) != null) return false
        val normalized = PlaylistNameValidator.normalize(name)
        viewModelScope.launch { playlistRepository.create(normalized) }
        return true
    }

    /** Renomme une playlist utilisateur. Sans effet sur une playlist épinglée ou si le nom est invalide. */
    fun renamePlaylist(id: Long, name: String): Boolean {
        if (isProtected(id) || PlaylistNameValidator.validate(name) != null) return false
        val normalized = PlaylistNameValidator.normalize(name)
        viewModelScope.launch { playlistRepository.rename(id, normalized) }
        return true
    }

    /** Supprime une playlist utilisateur. Sans effet sur une playlist épinglée. */
    fun deletePlaylist(id: Long): Boolean {
        if (isProtected(id)) return false
        viewModelScope.launch { playlistRepository.delete(id) }
        return true
    }

    /** Lit la playlist ; hors ligne, uniquement ses titres lisibles (téléchargés ou en cache). */
    fun playPlaylist(id: Long, shuffle: Boolean = false) {
        viewModelScope.launch {
            val downloads = downloadRepository.observeDownloads().first().completedDownloads()
            val downloadedIds = downloads.mapTo(HashSet()) { it.track.id }
            val isOffline = !networkMonitor.isOnline.first()
            val playableIds = if (isOffline) downloadedIds + offlineAvailability.cachedPlayableIds() else downloadedIds
            val tracks = tracksOf(id, downloads.map { it.track }).availableTracks(playableIds, isOffline)
            if (tracks.isNotEmpty()) {
                playbackController.play(tracks, 0, shuffle, QueueSources.playlist(id))
            } else if (isOffline) {
                messenger.show(UiText.of(R.string.lib_nothing_playable_offline))
            }
        }
    }

    /** Télécharge les titres de la playlist qui ne le sont pas encore (inutile pour « Téléchargés », impossible hors ligne). */
    fun downloadPlaylist(id: Long) {
        if (id == Playlist.DOWNLOADED_ID) return
        viewModelScope.launch {
            if (!networkMonitor.isOnline.first()) return@launch
            val downloadedIds = downloadRepository.observeDownloads().first().completedDownloads()
                .mapTo(HashSet()) { it.track.id }
            val toDownload = tracksOf(id, emptyList()).filterNot { it.id in downloadedIds }
            if (toDownload.isEmpty()) {
                messenger.show(UiText.of(R.string.lib_downloads_nothing_to_do))
                return@launch
            }
            notificationPermission.requestIfNeeded()
            downloadRepository.enqueue(toDownload)
            messenger.show(UiText.of(R.string.lib_downloads_started))
        }
    }

    /** Dernier état connu de l'index hors ligne ; une source défaillante ne doit pas empêcher la lecture. */
    private suspend fun OfflineAvailability.cachedPlayableIds(): Set<String> =
        try {
            withTimeoutOrNull(OFFLINE_INDEX_TIMEOUT_MS) { observePlayableIds().first() }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

    private suspend fun tracksOf(id: Long, downloadedTracks: List<Track>): List<Track> =
        if (id == Playlist.DOWNLOADED_ID) {
            downloadedTracks
        } else {
            playlistRepository.observePlaylist(id).first()?.entries?.map { it.track }.orEmpty()
        }

    private fun isProtected(id: Long): Boolean =
        id == Playlist.LIKED_ID || id == Playlist.DOWNLOADED_ID ||
            uiState.value.playlists.any { it.id == id && it.isPinned }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val OFFLINE_INDEX_TIMEOUT_MS = 2_000L
    }
}
