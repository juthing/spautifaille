package com.spautifaille.ui.downloads

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class DownloadsUiState(
    val isLoading: Boolean = true,
    /** En file, en cours, en pause ou en échec : RUNNING d'abord, puis l'attente (ordre d'arrivée), puis les échecs. */
    val active: List<Download> = emptyList(),
    /** Téléchargements terminés, du plus récent au plus ancien. */
    val completed: List<Download> = emptyList(),
    /** `null` tant que le calcul n'est pas terminé. */
    val storage: StorageUsage? = null,
) {
    val isEmpty: Boolean get() = !isLoading && active.isEmpty() && completed.isEmpty()
    val failedCount: Int get() = active.count { it.state == DownloadState.FAILED }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val playbackController: PlaybackController,
    private val messenger: UiMessenger,
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadRepository.observeDownloads(),
        downloadRepository.observeStorageUsage().map<StorageUsage, StorageUsage?> { it }.onStart { emit(null) },
    ) { downloads, storage ->
        DownloadsUiState(
            isLoading = false,
            active = downloads.filter { it.state != DownloadState.COMPLETED }.sortedWith(ActiveOrder),
            completed = downloads.filter { it.state == DownloadState.COMPLETED }.sortedByDescending { it.createdAt },
            storage = storage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DownloadsUiState())

    /** Lance la lecture de tous les titres terminés, à partir de l'élément [index] de la section « Terminés ». */
    fun play(index: Int) {
        val tracks = uiState.value.completed.map { it.track }
        if (index !in tracks.indices) return
        playbackController.play(tracks, index)
    }

    fun cancel(trackId: String) = launchAction { downloadRepository.cancel(trackId) }

    fun retry(trackId: String) = launchAction { downloadRepository.retry(trackId) }

    fun retryAllFailed() = launchAction {
        uiState.value.active.filter { it.state == DownloadState.FAILED }.forEach { downloadRepository.retry(it.track.id) }
    }

    fun delete(trackId: String) = launchAction {
        downloadRepository.delete(trackId)
        messenger.show(UiText.of(R.string.dl_deleted))
    }

    fun deleteAll() = launchAction {
        downloadRepository.deleteAll()
        messenger.show(UiText.of(R.string.dl_all_deleted))
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messenger.show(UiText.of(R.string.dl_action_failed))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        val ActiveOrder: Comparator<Download> = compareBy<Download> {
            when (it.state) {
                DownloadState.RUNNING -> 0
                DownloadState.QUEUED, DownloadState.PAUSED -> 1
                else -> 2
            }
        }.thenBy { it.createdAt }
    }
}
