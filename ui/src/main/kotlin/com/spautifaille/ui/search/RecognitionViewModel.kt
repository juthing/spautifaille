package com.spautifaille.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.recognition.RecognitionProgress
import com.spautifaille.domain.recognition.RecognizeMusicUseCase
import com.spautifaille.domain.recognition.RecognizedTrack
import com.spautifaille.ui.common.toAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** État de la feuille de reconnaissance musicale. [Idle] = feuille fermée. */
sealed interface RecognitionUiState {
    data object Idle : RecognitionUiState

    /** Permission micro refusée ; [permanentlyDenied] : le système ne la redemandera plus (aller dans les paramètres). */
    data class PermissionDenied(val permanentlyDenied: Boolean) : RecognitionUiState

    data class Listening(val elapsedMs: Long, val totalMs: Long) : RecognitionUiState {
        val progress: Float get() = if (totalMs <= 0) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    }

    data object Identifying : RecognitionUiState

    data class Found(val track: RecognizedTrack) : RecognitionUiState

    data object NoMatch : RecognitionUiState

    data class Failed(val error: AppError) : RecognitionUiState
}

/** Événements ponctuels (consommés une seule fois par l'écran de recherche). */
sealed interface RecognitionEvent {
    /** Un titre a été reconnu : lancer la recherche [query] (« titre artiste »). */
    data class SearchFor(val query: String) : RecognitionEvent
}

@HiltViewModel
class RecognitionViewModel @Inject constructor(
    private val recognizeMusic: RecognizeMusicUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RecognitionUiState>(RecognitionUiState.Idle)
    val uiState: StateFlow<RecognitionUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecognitionEvent>(Channel.BUFFERED)
    val events: Flow<RecognitionEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    /** Démarre l'écoute. À n'appeler que si la permission `RECORD_AUDIO` est accordée. */
    fun start() {
        job?.cancel()
        _uiState.value = RecognitionUiState.Listening(0, recognizeMusic.totalMs)
        job = viewModelScope.launch {
            try {
                recognizeMusic().collect { progress ->
                    when (progress) {
                        is RecognitionProgress.Listening ->
                            _uiState.value = RecognitionUiState.Listening(progress.elapsedMs, progress.totalMs)
                        RecognitionProgress.Identifying -> _uiState.value = RecognitionUiState.Identifying
                        is RecognitionProgress.Success -> {
                            _uiState.value = RecognitionUiState.Found(progress.track)
                            _events.trySend(RecognitionEvent.SearchFor(progress.track.searchQuery))
                        }
                        RecognitionProgress.NoMatch -> _uiState.value = RecognitionUiState.NoMatch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = RecognitionUiState.Failed(e.toAppError())
            }
        }
    }

    /** Résultat de la demande de permission refusée. */
    fun onPermissionDenied(permanentlyDenied: Boolean) {
        job?.cancel()
        _uiState.value = RecognitionUiState.PermissionDenied(permanentlyDenied)
    }

    /** Ferme la feuille et libère le micro (annuler, fermer, ou retour système). */
    fun dismiss() {
        job?.cancel()
        job = null
        _uiState.value = RecognitionUiState.Idle
    }

    /** L'application passe en arrière-plan : on n'écoute plus (le micro y est de toute façon coupé par Android). */
    fun onAppStopped() {
        when (_uiState.value) {
            is RecognitionUiState.Listening, RecognitionUiState.Identifying -> dismiss()
            else -> Unit
        }
    }
}
