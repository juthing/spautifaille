package com.spautifaille.ui.youtube

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.youtube.AccountRepository
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.YouTubePlaylistSync
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toAppError
import com.spautifaille.ui.common.toMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Actions YouTube d'une playlist locale (écran de détail) : « Délier » et « Publier sur YouTube ». L'identifiant de
 * playlist est lu dans `SavedStateHandle["id"]` (argument de `PlaylistRoute`).
 */
@HiltViewModel
class PlaylistYouTubeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accounts: AccountRepository,
    private val playlistSync: YouTubePlaylistSync,
    private val messenger: UiMessenger,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle["id"] ?: 0L
    private val isBusy = MutableStateFlow(false)

    /** Un compte est connecté : « Publier sur YouTube » est proposé. */
    val canPublish: StateFlow<Boolean> = accounts.accountState
        .map { it is AccountState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val busy: StateFlow<Boolean> = isBusy.asStateFlow()

    fun unlink() {
        viewModelScope.launch {
            try {
                playlistSync.unlink(playlistId)
                messenger.show(UiText.of(R.string.yt_unlinked))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messenger.show(UiText.of(e.toAppError().toMessage()))
            }
        }
    }

    fun publish() {
        if (isBusy.value) return
        isBusy.value = true
        viewModelScope.launch {
            try {
                playlistSync.publish(playlistId)
                messenger.show(UiText.of(R.string.yt_published))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messenger.show(UiText.of(e.toAppError().toMessage()))
            } finally {
                isBusy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
