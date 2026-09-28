package com.spautifaille.ui.artist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.ArtistDetails
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.library.toLibraryAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ArtistStatus {
    data object Loading : ArtistStatus
    data class Error(val error: AppError) : ArtistStatus
    data class Content(val details: ArtistDetails) : ArtistStatus
}

@Immutable
data class ArtistUiState(
    val status: ArtistStatus = ArtistStatus.Loading,
    val isSubscribed: Boolean = false,
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val streamRepository: StreamRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    /** Argument de navigation `url` (String) : URL de la chaîne. */
    private val url: String = savedStateHandle.get<String>(ARG_URL).orEmpty()

    private val status = MutableStateFlow<ArtistStatus>(ArtistStatus.Loading)

    val uiState: StateFlow<ArtistUiState> = combine(
        status,
        libraryRepository.observeIsSubscribed(url),
    ) { status, subscribed ->
        ArtistUiState(status = status, isSubscribed = subscribed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ArtistUiState())

    init {
        load()
    }

    fun load() {
        status.value = ArtistStatus.Loading
        viewModelScope.launch {
            status.value = try {
                ArtistStatus.Content(streamRepository.artist(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArtistStatus.Error(e.toLibraryAppError())
            }
        }
    }

    fun toggleSubscription() {
        val details = (status.value as? ArtistStatus.Content)?.details ?: return
        viewModelScope.launch {
            // La clé d'abonnement est l'URL de navigation, celle observée par observeIsSubscribed(url).
            if (uiState.value.isSubscribed) {
                libraryRepository.unsubscribe(url)
            } else {
                libraryRepository.subscribe(details.artist.copy(url = url))
            }
        }
    }

    /** Lit les titres de l'artiste à partir de [index]. */
    fun playFrom(index: Int) {
        val tracks = (status.value as? ArtistStatus.Content)?.details?.tracks.orEmpty()
        if (tracks.isEmpty()) return
        playbackController.play(tracks, index.coerceIn(tracks.indices), false)
    }

    fun playAll(shuffle: Boolean) {
        val tracks = (status.value as? ArtistStatus.Content)?.details?.tracks.orEmpty()
        if (tracks.isEmpty()) return
        playbackController.play(tracks, 0, shuffle)
    }

    companion object {
        /** Clé de l'argument de navigation (route `ArtistRoute(url: String)`). */
        const val ARG_URL = "url"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
