package com.spautifaille.ui.youtube

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.youtube.AccountRepository
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.LibrarySync
import com.spautifaille.domain.youtube.SyncStatus
import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class YouTubeAccountUiState(
    val isLoaded: Boolean = false,
    val accountState: AccountState = AccountState.SignedOut,
    val status: SyncStatus = SyncStatus(),
    val likesMusicOnly: Boolean = false,
) {
    /** Profil connu (connecté ou à reconnecter). */
    val account: YouTubeAccount?
        get() = when (accountState) {
            is AccountState.SignedIn -> accountState.account
            is AccountState.ReauthRequired -> accountState.account
            AccountState.SignedOut -> null
        }

    val isSignedIn: Boolean get() = accountState is AccountState.SignedIn
    val needsReauth: Boolean get() = accountState is AccountState.ReauthRequired

    /** Badge d'erreur de l'avatar : dernière synchro échouée ou reconnexion nécessaire. */
    val hasProblem: Boolean get() = needsReauth || (isSignedIn && status.lastError != null)
}

/** Compte YouTube : état partagé par l'avatar des barres d'application et la page de réglages du compte. */
@HiltViewModel
class YouTubeAccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val sync: LibrarySync,
    private val webSession: WebSessionCleaner,
    private val messenger: UiMessenger,
) : ViewModel() {

    val uiState: StateFlow<YouTubeAccountUiState> = combine(
        accounts.accountState,
        sync.status,
        sync.likesMusicOnly,
    ) { account, status, musicOnly ->
        YouTubeAccountUiState(isLoaded = true, accountState = account, status = status, likesMusicOnly = musicOnly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), YouTubeAccountUiState())

    fun syncNow() {
        sync.syncNow()
        messenger.show(UiText.of(R.string.yt_sync_started))
    }

    fun setLikesMusicOnly(enabled: Boolean) {
        viewModelScope.launch { sync.setLikesMusicOnly(enabled) }
    }

    /** Efface identifiants, file et liens côté données, puis les cookies de la WebView. Les données locales restent. */
    fun signOut() {
        viewModelScope.launch {
            try {
                accounts.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Même en cas d'échec partiel, les cookies de la WebView doivent disparaître.
            }
            webSession.clear()
            messenger.show(UiText.of(R.string.yt_signed_out))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
