package com.spautifaille.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.ui.network.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime
import javax.inject.Inject

enum class Greeting { MORNING, AFTERNOON, EVENING }

/** Raccourci de l'accueil : une playlist locale ([isLiked] pour « Titres likés »). */
@Immutable
data class HomeShortcut(
    val playlistId: Long,
    val name: String,
    val thumbnailUrl: String?,
    val isLiked: Boolean,
)

@Immutable
data class HomeUiState(
    val greeting: Greeting = Greeting.MORNING,
    /** Titres récemment écoutés, sans doublon, du plus récent au plus ancien. */
    val recent: List<Track> = emptyList(),
    /** « Titres likés » en premier, puis les playlists utilisateur les plus récemment modifiées. */
    val shortcuts: List<HomeShortcut> = emptyList(),
    /** Artistes suivis, dans l'ordre du repository (aperçu limité). */
    val artists: List<Artist> = emptyList(),
    /** Faux hors ligne : les recommandations (réseau) sont alors masquées. */
    val isOnline: Boolean = true,
    val isLoading: Boolean = true,
) {
    /** Rien d'écouté ni de suivi : on accueille l'utilisateur avec une invitation à chercher de la musique. */
    val isFirstLaunch: Boolean get() = !isLoading && recent.isEmpty() && artists.isEmpty()
}

@HiltViewModel
class HomeViewModel internal constructor(
    libraryRepository: LibraryRepository,
    playlistRepository: PlaylistRepository,
    networkMonitor: NetworkMonitor,
    private val playbackController: PlaybackController,
    private val currentTime: () -> LocalTime,
) : ViewModel() {

    @Inject
    constructor(
        libraryRepository: LibraryRepository,
        playlistRepository: PlaylistRepository,
        networkMonitor: NetworkMonitor,
        playbackController: PlaybackController,
    ) : this(libraryRepository, playlistRepository, networkMonitor, playbackController, LocalTime::now)

    val uiState: StateFlow<HomeUiState> = combine(
        libraryRepository.observeHistory(HISTORY_LIMIT),
        playlistRepository.observePlaylists(),
        libraryRepository.observeSubscriptions(),
        networkMonitor.isOnline,
    ) { history, playlists, subscriptions, isOnline ->
        HomeUiState(
            greeting = greetingFor(currentTime().hour),
            recent = history.map { it.track }.distinctBy { it.id }.take(RECENT_COUNT),
            shortcuts = shortcutsFor(playlists),
            artists = subscriptions.take(ARTIST_COUNT),
            isOnline = isOnline,
            isLoading = false,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        HomeUiState(greeting = greetingFor(currentTime().hour)),
    )

    /** Lit le titre choisi et place à sa suite les autres titres récents. */
    fun onRecentClick(track: Track) {
        val recent = uiState.value.recent
        val index = recent.indexOfFirst { it.id == track.id }
        if (index >= 0) playbackController.play(recent, startIndex = index) else playbackController.play(listOf(track))
    }

    companion object {
        const val HISTORY_LIMIT = 40
        const val RECENT_COUNT = 15
        const val ARTIST_COUNT = 15

        /** Nombre maximal de raccourcis (« Titres likés » compris). */
        const val SHORTCUT_COUNT = 6
        private const val STOP_TIMEOUT_MS = 5_000L

        fun greetingFor(hour: Int): Greeting = when (hour) {
            in 5..11 -> Greeting.MORNING
            in 12..17 -> Greeting.AFTERNOON
            else -> Greeting.EVENING
        }

        /**
         * « Titres likés » d'abord (toujours présent, même si la playlist système n'est pas encore lue), puis
         * les playlists utilisateur de la plus récemment modifiée (ajout, renommage) à la plus ancienne.
         */
        internal fun shortcutsFor(playlists: List<Playlist>): List<HomeShortcut> {
            val liked = playlists.firstOrNull { it.id == Playlist.LIKED_ID }
            val likedShortcut = HomeShortcut(
                playlistId = Playlist.LIKED_ID,
                name = liked?.name.orEmpty(),
                thumbnailUrl = liked?.thumbnailUrl,
                isLiked = true,
            )
            val others = playlists
                .filterNot { it.isSystem || it.id == Playlist.LIKED_ID }
                .sortedByDescending { it.updatedAt }
                .take(SHORTCUT_COUNT - 1)
                .map { HomeShortcut(it.id, it.name, it.thumbnailUrl, isLiked = false) }
            return listOf(likedShortcut) + others
        }
    }
}
