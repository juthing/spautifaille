package com.spautifaille.ui.player

import androidx.lifecycle.ViewModel
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Façade UI du lecteur : expose l'état et la position (flux séparés pour ne pas recomposer tout l'écran à ~4 Hz)
 * et délègue les actions à [PlaybackController]. Aucune logique de lecture ici.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    val state: StateFlow<PlayerState> = controller.state
    val position: StateFlow<PlaybackPosition> = controller.position

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun toggleShuffle() = controller.setShuffle(!state.value.shuffleEnabled)
    fun cycleRepeatMode() = controller.cycleRepeatMode()
    fun toggleLike() = controller.toggleLikeCurrent()
    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    fun setSleepTimerMinutes(minutes: Int) =
        controller.setSleepTimer(TimeUnit.MINUTES.toMillis(minutes.toLong()))

    fun setSleepTimerEndOfTrack() = controller.setSleepTimerEndOfTrack()
    fun cancelSleepTimer() = controller.cancelSleepTimer()

    fun skipToQueueItem(index: Int) = controller.skipToQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = controller.moveQueueItem(from, to)
    fun removeQueueItem(index: Int) = controller.removeQueueItem(index)
    fun clearQueue() = controller.clearQueue()
}
