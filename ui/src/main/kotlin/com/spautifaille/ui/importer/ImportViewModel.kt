package com.spautifaille.ui.importer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportRepository
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ImportUiState(
    val url: String = "",
    /** Le lien saisi est non vide mais n'est pas une playlist YouTube reconnue. */
    val urlInvalid: Boolean = false,
    val canImportUrl: Boolean = false,
    /** Lecture de la source en cours (fichier ou pages de la playlist YouTube). */
    val isStarting: Boolean = false,
    val isLoadingJobs: Boolean = true,
    /** Du plus récent au plus ancien. */
    val jobs: List<ImportJob> = emptyList(),
)

/** Événements ponctuels (snackbar). */
sealed interface ImportEvent {
    data class Started(val count: Int) : ImportEvent
    data class Failed(val message: UiText) : ImportEvent
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importRepository: ImportRepository,
    private val streamRepository: StreamRepository,
    private val fileInfo: ImportFileInfoProvider,
    private val notificationPermission: NotificationPermissionRequester,
) : ViewModel() {

    private data class Local(val url: String = "", val isStarting: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val _events = Channel<ImportEvent>(Channel.BUFFERED)
    val events: Flow<ImportEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ImportUiState> = combine(local, importRepository.observeJobs()) { local, jobs ->
        val trimmed = local.url.trim()
        val valid = trimmed.isNotEmpty() && streamRepository.isPlaylistUrl(trimmed)
        ImportUiState(
            url = local.url,
            urlInvalid = trimmed.isNotEmpty() && !valid,
            canImportUrl = valid && !local.isStarting,
            isStarting = local.isStarting,
            isLoadingJobs = false,
            jobs = jobs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ImportUiState())

    fun onUrlChanged(url: String) {
        local.update { it.copy(url = url) }
    }

    /** Lance l'import de la playlist YouTube saisie (sans effet si le lien n'est pas valide). */
    fun importUrl() {
        val url = local.value.url.trim()
        if (url.isEmpty() || !streamRepository.isPlaylistUrl(url)) return
        start(onSuccess = { local.update { it.copy(url = "") } }) { ImportSource.Url(url) }
    }

    /** Fichier choisi dans le sélecteur système ([uri] = `content://…`). */
    fun onFilePicked(uri: String) {
        start {
            val info = fileInfo.describe(uri)
            ImportSource.File(uri, info.displayName, info.mimeType)
        }
    }

    fun deleteJob(jobId: Long) {
        viewModelScope.launch {
            try {
                importRepository.deleteJob(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ImportEvent.Failed(e.toImportMessage()))
            }
        }
    }

    private fun start(onSuccess: () -> Unit = {}, source: suspend () -> ImportSource) {
        if (local.value.isStarting) return
        notificationPermission.requestIfNeeded()
        local.update { it.copy(isStarting = true) }
        viewModelScope.launch {
            try {
                val ids = importRepository.start(source())
                onSuccess()
                _events.send(ImportEvent.Started(ids.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ImportEvent.Failed(e.toImportMessage()))
            } finally {
                local.update { it.copy(isStarting = false) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
