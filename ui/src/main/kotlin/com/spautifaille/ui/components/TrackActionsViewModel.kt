package com.spautifaille.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toAppError
import com.spautifaille.ui.common.toMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** État propre au titre sélectionné dans [TrackActionsSheet]. */
data class TrackActionsState(
    val isLiked: Boolean = false,
    val download: Download? = null,
) {
    val downloadStatus: DownloadStatus
        get() = when (download?.state) {
            null, DownloadState.FAILED -> DownloadStatus.NONE
            DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED -> DownloadStatus.IN_PROGRESS
            DownloadState.COMPLETED -> DownloadStatus.DONE
        }
}

enum class DownloadStatus { NONE, IN_PROGRESS, DONE }

/**
 * ViewModel partagé des actions sur un titre (file, like, téléchargement, ajout à une playlist).
 * Les retours utilisateur passent par [UiMessenger] (snackbar global).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrackActionsViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val messenger: UiMessenger,
    private val notificationPermission: NotificationPermissionRequester,
) : ViewModel() {

    private val selectedTrackId = MutableStateFlow<String?>(null)

    val state: StateFlow<TrackActionsState> = selectedTrackId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(TrackActionsState())
            } else {
                combine(
                    libraryRepository.observeIsLiked(id),
                    downloadRepository.observeDownload(id),
                ) { liked, download -> TrackActionsState(isLiked = liked, download = download) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackActionsState())

    /** Playlists locales modifiables (la playlist système « Titres likés » passe par le bouton J'aime). */
    val playlists: StateFlow<List<Playlist>> = playlistRepository.observePlaylists()
        .map { list -> list.filterNot { it.isSystem } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(trackId: String) {
        selectedTrackId.value = trackId
    }

    fun playNext(track: Track) {
        playbackController.playNext(listOf(track))
        messenger.show(UiText.of(R.string.snack_play_next))
    }

    fun addToQueue(track: Track) {
        playbackController.addToQueue(listOf(track))
        messenger.show(UiText.of(R.string.snack_added_to_queue))
    }

    fun toggleLike(track: Track) = launchAction {
        val liked = libraryRepository.toggleLike(track)
        messenger.show(UiText.of(if (liked) R.string.snack_liked else R.string.snack_unliked))
    }

    /** Télécharge, annule ou supprime selon l'état courant du téléchargement. */
    fun toggleDownload(track: Track) = launchAction {
        when (state.value.downloadStatus) {
            DownloadStatus.NONE -> {
                if (state.value.download?.state == DownloadState.FAILED) {
                    downloadRepository.retry(track.id)
                } else {
                    notificationPermission.requestIfNeeded()
                    downloadRepository.enqueue(listOf(track))
                }
                messenger.show(UiText.of(R.string.snack_download_queued))
            }
            DownloadStatus.IN_PROGRESS -> {
                downloadRepository.cancel(track.id)
                messenger.show(UiText.of(R.string.snack_download_cancelled))
            }
            DownloadStatus.DONE -> {
                downloadRepository.delete(track.id)
                messenger.show(UiText.of(R.string.snack_download_deleted))
            }
        }
    }

    fun addToPlaylist(playlist: Playlist, tracks: List<Track>) = launchAction {
        playlistRepository.addTracks(playlist.id, tracks)
        messenger.show(UiText.of(R.string.snack_added_to_playlist, playlist.name))
    }

    fun createPlaylist(name: String, tracks: List<Track>) = launchAction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launchAction
        playlistRepository.create(trimmed, tracks)
        messenger.show(UiText.of(R.string.snack_added_to_playlist, trimmed))
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messenger.show(UiText.of(e.toAppError().toMessage()))
            }
        }
    }
}
