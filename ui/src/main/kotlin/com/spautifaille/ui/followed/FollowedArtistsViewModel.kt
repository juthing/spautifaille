package com.spautifaille.ui.followed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class FollowedArtistsUiState(
    val isLoading: Boolean = true,
    val artists: List<Artist> = emptyList(),
)

/** Liste complète des artistes suivis (abonnements), avec désabonnement. */
@HiltViewModel
class FollowedArtistsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val messenger: UiMessenger,
) : ViewModel() {

    val uiState: StateFlow<FollowedArtistsUiState> = libraryRepository.observeSubscriptions()
        .map { FollowedArtistsUiState(isLoading = false, artists = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FollowedArtistsUiState())

    fun unsubscribe(artist: Artist) {
        viewModelScope.launch {
            libraryRepository.unsubscribe(artist.url)
            messenger.show(UiText.of(R.string.followed_unsubscribed, artist.name))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
