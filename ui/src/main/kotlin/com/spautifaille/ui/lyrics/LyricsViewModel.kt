package com.spautifaille.ui.lyrics

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.lyrics.LyricLine
import com.spautifaille.domain.lyrics.Lyrics
import com.spautifaille.domain.lyrics.displayLines
import com.spautifaille.domain.lyrics.indexAt
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.repository.LyricsRepository
import com.spautifaille.ui.common.ElapsedClock
import com.spautifaille.ui.common.toAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** État de l'écran de paroles. */
@Immutable
sealed interface LyricsUiState {
    data object Loading : LyricsUiState

    /** [lines] : lignes à afficher (pauses courtes masquées) ; [activeIndex] = ligne courante, -1 avant la première. */
    data class Synced(val lines: List<LyricLine>, val activeIndex: Int, val source: String) : LyricsUiState

    data class Plain(val text: String, val source: String) : LyricsUiState
    data class Instrumental(val source: String) : LyricsUiState
    data object NotFound : LyricsUiState
    data class Error(val error: AppError) : LyricsUiState
}

/**
 * Paroles du titre en cours. Le chargement ne démarre qu'à l'abonnement (écran de paroles visible) et suit les
 * changements de titre. La ligne courante vient de la position du lecteur (~4 Hz) interpolée avec l'horloge
 * entre deux émissions quand la lecture est en cours : le changement de ligne tombe donc à l'horodatage exact
 * (l'état n'est ré-émis qu'à chaque changement de ligne, pas à chaque tick).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val repository: LyricsRepository,
    private val clock: ElapsedClock,
) : ViewModel() {

    private val retryCount = MutableStateFlow(0)

    val uiState: StateFlow<LyricsUiState> = combine(
        controller.state.map { it.currentTrack }.distinctUntilChangedBy { it?.id },
        retryCount,
    ) { track, _ -> track }
        .flatMapLatest { track -> if (track == null) flowOf(LyricsUiState.NotFound) else load(track) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LyricsUiState.Loading)

    fun retry() {
        retryCount.value++
    }

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    private fun load(track: Track): Flow<LyricsUiState> = flow {
        emit(LyricsUiState.Loading)
        val lyrics = try {
            repository.lyrics(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(LyricsUiState.Error(e.toAppError()))
            return@flow
        }
        when (lyrics) {
            null -> emit(LyricsUiState.NotFound)
            is Lyrics.Plain -> emit(LyricsUiState.Plain(lyrics.text, lyrics.source))
            is Lyrics.Instrumental -> emit(LyricsUiState.Instrumental(lyrics.source))
            is Lyrics.Synced -> {
                val lines = lyrics.lines.displayLines()
                if (lines.none { !it.isBreak }) {
                    emit(LyricsUiState.NotFound)
                } else {
                    emitAll(activeIndex(lines).map { LyricsUiState.Synced(lines, it, lyrics.source) })
                }
            }
        }
    }

    /**
     * Index de la ligne courante. Chaque émission de position (ou changement lecture/pause/vitesse) relance une
     * boucle qui estime la position avec l'horloge et dort jusqu'au prochain horodatage.
     */
    private fun activeIndex(lines: List<LyricLine>): Flow<Int> =
        combine(
            controller.position,
            controller.state.map { it.isPlaying to it.speed }.distinctUntilChanged(),
        ) { position, playback -> Triple(position, playback.first, playback.second) }
            .flatMapLatest { (position, playing, speed) -> interpolatedIndex(lines, position, playing, speed) }
            .distinctUntilChanged()

    private fun interpolatedIndex(
        lines: List<LyricLine>,
        position: PlaybackPosition,
        playing: Boolean,
        speed: Float,
    ): Flow<Int> = flow {
        val startedAt = clock.elapsedRealtimeMs()
        val rate = if (speed > 0f) speed else 1f
        while (true) {
            val elapsed = if (playing) ((clock.elapsedRealtimeMs() - startedAt) * rate).toLong() else 0L
            val estimated = position.positionMs + elapsed
            val index = lines.indexAt(estimated)
            emit(index)
            if (!playing) break
            val next = lines.getOrNull(index + 1) ?: break
            delay(((next.timeMs - estimated) / rate).toLong().coerceAtLeast(MIN_WAIT_MS))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MIN_WAIT_MS = 10L
    }
}
