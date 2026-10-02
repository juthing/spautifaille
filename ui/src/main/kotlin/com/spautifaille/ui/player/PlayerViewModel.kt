package com.spautifaille.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.ui.common.ElapsedClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun toggleShuffle() = controller.setShuffle(!state.value.shuffleEnabled)
    fun cycleRepeatMode() = controller.cycleRepeatMode()
    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    /** Like / unlike du titre courant. Aucun message : le bouton j'aime porte lui-même son animation de confirmation. */
    fun toggleLike() = controller.toggleLikeCurrent()

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
    }
}
