package com.spautifaille.player.sleep

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** État de la minuterie côté service. Les temps utilisent l'horloge `SystemClock.elapsedRealtime`. */
sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class At(val endsAtElapsedMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/** Actions dont la minuterie a besoin sur le lecteur (abstraites pour rester testables sans ExoPlayer). */
interface SleepTimerPlayer {
    fun pause()
    var volume: Float
    /** Mode « fin du titre » : le lecteur se met en pause à la fin de chaque élément. */
    fun setPauseAtEndOfMediaItems(enabled: Boolean)
}

/**
 * Minuterie de sommeil.
 *
 * - Mode durée : à l'échéance, met en pause après un fondu de volume sur les [fadeDurationMs] dernières ms.
 * - Mode fin du titre : active `pauseAtEndOfMediaItems` ; le service appelle [onItemEnded] quand le lecteur s'est
 *   mis en pause à la fin de l'élément.
 *
 * Toutes les méthodes sont à appeler depuis le thread principal (celui du lecteur).
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val player: SleepTimerPlayer,
    private val onStateChanged: (SleepTimerState) -> Unit,
    private val onFinished: () -> Unit,
    private val fadeDurationMs: Long = DEFAULT_FADE_MS,
    private val fadeStepMs: Long = DEFAULT_FADE_STEP_MS,
) {
    var state: SleepTimerState = SleepTimerState.Off
        private set

    private var job: Job? = null
    private var volumeNeedsRestore = false

    fun start(durationMs: Long) {
        require(durationMs > 0) { "durationMs must be > 0" }
        stopInternal()
        restoreVolume()
        val endsAt = elapsedRealtimeMs() + durationMs
        setState(SleepTimerState.At(endsAt))
        val fadeWindow = minOf(fadeDurationMs, durationMs)
        job = scope.launch {
            delay((endsAt - fadeWindow - elapsedRealtimeMs()).coerceAtLeast(0))
            while (true) {
                val remaining = endsAt - elapsedRealtimeMs()
                if (remaining <= 0) break
                volumeNeedsRestore = true
                player.volume = (remaining.toFloat() / fadeWindow).coerceIn(0f, 1f)
                delay(minOf(fadeStepMs, remaining))
            }
            player.pause()
            // Le volume est restauré à la reprise (onPlaybackResumed) pour éviter un « pop » pendant la vidange audio.
            job = null
            setState(SleepTimerState.Off)
            onFinished()
        }
    }

    fun startEndOfTrack() {
        stopInternal()
        restoreVolume()
        player.setPauseAtEndOfMediaItems(true)
        setState(SleepTimerState.EndOfTrack)
    }

    fun cancel() {
        val wasActive = state != SleepTimerState.Off
        stopInternal()
        restoreVolume()
        if (wasActive) setState(SleepTimerState.Off)
    }

    /** Le lecteur s'est mis en pause à la fin d'un élément (ou la file est terminée). */
    fun onItemEnded() {
        if (state != SleepTimerState.EndOfTrack) return
        player.setPauseAtEndOfMediaItems(false)
        setState(SleepTimerState.Off)
        onFinished()
    }

    /** L'utilisateur a relancé la lecture : rétablit le volume plein après un fondu terminé. */
    fun onPlaybackResumed() {
        if (job == null) restoreVolume()
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        if (state == SleepTimerState.EndOfTrack) player.setPauseAtEndOfMediaItems(false)
    }

    private fun restoreVolume() {
        if (volumeNeedsRestore) {
            player.volume = 1f
            volumeNeedsRestore = false
        }
    }

    private fun setState(newState: SleepTimerState) {
        if (state == newState) return
        state = newState
        onStateChanged(newState)
    }

    companion object {
        const val DEFAULT_FADE_MS = 10_000L
        const val DEFAULT_FADE_STEP_MS = 250L
    }
}
