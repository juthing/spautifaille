package com.spautifaille.player.history

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.spautifaille.domain.model.Track
import com.spautifaille.player.MediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Règle d'historique : une écoute est enregistrée quand le titre a été joué au moins [MIN_PLAYED_MS] ms
 * ou la moitié de sa durée (le premier des deux), et une seule fois par occurrence.
 */
class PlayTimeTracker {
    private var playedMs = 0L
    private var recorded = false

    fun reset() {
        playedMs = 0
        recorded = false
    }

    fun addPlayed(deltaMs: Long) {
        if (deltaMs > 0) playedMs += deltaMs
    }

    /** Vrai (une seule fois) quand le seuil est atteint. [durationMs] <= 0 : durée inconnue. */
    fun shouldRecord(durationMs: Long): Boolean {
        if (recorded) return false
        val reached = playedMs >= MIN_PLAYED_MS || (durationMs > 0 && playedMs * 2 >= durationMs)
        if (reached) recorded = true
        return reached
    }

    companion object {
        const val MIN_PLAYED_MS = 30_000L
    }
}

/**
 * Écoute le lecteur et appelle [record] (non bloquant) pour chaque écoute qui compte. Le temps joué est cumulé à l'horloge murale
 * pendant `isPlaying` (le buffering et la pause ne comptent pas, les seeks ne font pas tricher le compteur).
 * Une lecture en boucle (REPEAT_MODE_ONE) recompte comme une nouvelle occurrence.
 */
class PlayHistoryRecorder(
    private val player: Player,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val record: (Track) -> Unit,
    private val tickMs: Long = TICK_MS,
) : Player.Listener {

    private val tracker = PlayTimeTracker()
    private var tickJob: Job? = null
    private var lastTickAt = 0L

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        tracker.reset()
        lastTickAt = elapsedRealtimeMs()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        tickJob?.cancel()
        if (isPlaying) {
            lastTickAt = elapsedRealtimeMs()
            tickJob = scope.launch {
                while (true) {
                    delay(tickMs)
                    tick()
                }
            }
        } else {
            tick()
        }
    }

    fun release() {
        tickJob?.cancel()
    }

    private fun tick() {
        val now = elapsedRealtimeMs()
        tracker.addPlayed(now - lastTickAt)
        lastTickAt = now
        val item = player.currentMediaItem ?: return
        val track = MediaItemMapper.toTrack(item)
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: track.durationMs ?: 0L
        if (tracker.shouldRecord(duration)) {
            record(track)
        }
    }

    companion object {
        const val TICK_MS = 1_000L
    }
}
