package com.spautifaille.ui.youtube

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.youtube.AccountRepository
import com.spautifaille.domain.youtube.YouTubeCredentials
import com.spautifaille.ui.R
import com.spautifaille.ui.common.toAppError
import com.spautifaille.ui.common.toMessage
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

@Immutable
data class YouTubeLoginUiState(
    /** Validation des identifiants capturés en cours (menu de compte InnerTube). */
    val isChecking: Boolean = false,
    /** Message d'échec à afficher (ressource), ou `null`. */
    val errorMessage: Int? = null,
    /** Incrémenté pour recharger la page de connexion après un échec. */
    val attempt: Int = 0,
)

sealed interface YouTubeLoginEvent {
    /** Connexion réussie : l'écran se ferme. */
    data object SignedIn : YouTubeLoginEvent
}

/** Écran de connexion : reçoit les identifiants capturés par la WebView et les valide auprès de YouTube. */
@HiltViewModel
class YouTubeLoginViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val webSession: WebSessionCleaner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeLoginUiState())
    val uiState: StateFlow<YouTubeLoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<YouTubeLoginEvent>(Channel.BUFFERED)
    val events: Flow<YouTubeLoginEvent> = _events.receiveAsFlow()

    fun onCredentials(credentials: YouTubeCredentials) {
        if (_uiState.value.isChecking) return
        _uiState.update { it.copy(isChecking = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                accounts.signIn(credentials)
                // Les seuls exemplaires des cookies sont désormais chiffrés dans le stockage de l'app.
                webSession.clear()
                _uiState.update { it.copy(isChecking = false) }
                _events.send(YouTubeLoginEvent.SignedIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when (val error = e.toAppError()) {
                    AppError.YouTubeAuthRequired -> R.string.yt_login_refused
                    else -> error.toMessage()
                }
                _uiState.update { it.copy(isChecking = false, errorMessage = message) }
            }
        }
    }

    /** La page est chargée mais la configuration YouTube Music (VISITOR_DATA) est restée introuvable. */
    fun onCaptureFailed() {
        _uiState.update { it.copy(isChecking = false, errorMessage = R.string.yt_login_capture_failed) }
    }

    /** Ferme le message d'échec et relance la page de connexion. */
    fun retry() {
        _uiState.update { it.copy(errorMessage = null, attempt = it.attempt + 1) }
    }
}
