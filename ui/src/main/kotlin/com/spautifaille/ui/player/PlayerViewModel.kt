package com.spautifaille.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.ui.R
import com.spautifaille.ui.common.ElapsedClock
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Façade UI du lecteur : expose l'état et la position (flux séparés pour ne pas recomposer tout l'écran à ~4 Hz)
 * et délègue les actions à [PlaybackController]. Aucune logique de lecture ici.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val messenger: UiMessenger,
    private val clock: ElapsedClock,
) : ViewModel() {

    val state: StateFlow<PlayerState> = controller.state
    val position: StateFlow<PlaybackPosition> = controller.position

    /**
     * Temps restant de la minuterie de sommeil (ms), recalculé chaque seconde à partir de l'échéance
     * (`endsAtElapsedMs`) : le lecteur ne publie [SleepTimer.At.remainingMs] qu'aux changements d'état.
     * `null` si aucune minuterie chronométrée n'est active ; le ticker ne tourne que dans ce cas et
     * seulement tant que le flux est collecté.
     */
    val sleepTimerRemaining: StateFlow<Long?> = controller.state
        .map { it.sleepTimer }
        .distinctUntilChanged()
        .flatMapLatest<SleepTimer, Long?> { timer ->
            when (timer) {
                is SleepTimer.At -> flow {
                    while (true) {
                        val remaining = remainingOf(timer)
                        emit(remaining)
                        if (remaining <= 0L) break
                        delay(TICK_MS)
                    }
                }
                else -> flowOf(null)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            (controller.state.value.sleepTimer as? SleepTimer.At)?.let { remainingOf(it) },
        )

    private var likeFeedbackJob: Job? = null

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun toggleShuffle() = controller.setShuffle(!state.value.shuffleEnabled)
    fun cycleRepeatMode() = controller.cycleRepeatMode()
    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    /**
     * Like / unlike du titre courant, puis snackbar « Ajouté aux Titres likés » / « Retiré des Titres likés »
     * une fois le nouvel état observé sur le même titre (un changement de titre n'affiche rien).
     */
    fun toggleLike() {
        val before = state.value
        val trackId = before.currentTrack?.id
        controller.toggleLikeCurrent()
        if (trackId == null) return
        likeFeedbackJob?.cancel()
        likeFeedbackJob = viewModelScope.launch {
            val after = withTimeoutOrNull(LIKE_FEEDBACK_TIMEOUT_MS) {
                state.first { it.currentTrack?.id != trackId || it.isCurrentLiked != before.isCurrentLiked }
            } ?: return@launch
            if (after.currentTrack?.id != trackId) return@launch
            messenger.show(UiText.of(if (after.isCurrentLiked) R.string.snack_liked else R.string.snack_unliked))
        }
    }

    fun setSleepTimerMinutes(minutes: Int) =
        controller.setSleepTimer(TimeUnit.MINUTES.toMillis(minutes.toLong()))

    fun setSleepTimerEndOfTrack() = controller.setSleepTimerEndOfTrack()
    fun cancelSleepTimer() = controller.cancelSleepTimer()

    fun skipToQueueItem(index: Int) = controller.skipToQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = controller.moveQueueItem(from, to)
    fun removeQueueItem(index: Int) = controller.removeQueueItem(index)
    fun clearQueue() = controller.clearQueue()

    private fun remainingOf(timer: SleepTimer.At): Long =
        (timer.endsAtElapsedMs - clock.elapsedRealtimeMs()).coerceAtLeast(0L)

    private companion object {
        const val TICK_MS = 1_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val LIKE_FEEDBACK_TIMEOUT_MS = 3_000L
    }
}
