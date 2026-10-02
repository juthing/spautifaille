package com.spautifaille.domain.player

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class RepeatMode { OFF, ALL, ONE }

data class QueueItem(
    /** Identifiant unique de l'élément dans la file (un même titre peut apparaître deux fois). */
    val uid: String,
    val track: Track,
)

sealed interface SleepTimer {
    data object Off : SleepTimer
    /** S'arrête à [endsAtElapsedMs] (horloge `SystemClock.elapsedRealtime`). */
    data class At(val endsAtElapsedMs: Long, val remainingMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

data class PlayerState(
    val isConnected: Boolean = false,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Vrai si la lecture est demandée (play) même si en buffering. */
    val playWhenReady: Boolean = false,
    val durationMs: Long = 0,
    val queue: List<QueueItem> = emptyList(),
    /** Index de l'élément courant dans [queue] (ordre d'affichage = ordre de la file, pas du shuffle). */
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val speed: Float = 1f,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    val isCurrentLiked: Boolean = false,
    val isCurrentOffline: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /**
     * Origine de la file en cours (voir [QueueSources]) : renseignée par `play(..., sourceId)` et portée par le
     * titre courant. `null` si la file ne vient d'aucune source identifiée (titre ajouté à la main, file restaurée).
     */
    val queueSourceId: String? = null,
)

/** Identifiants d'origine d'une file de lecture (`PlayerState.queueSourceId`). */
object QueueSources {
    /** File lancée depuis la playlist locale [playlistId] (y compris « Titres likés » et « Téléchargés »). */
    fun playlist(playlistId: Long): String = "playlist:$playlistId"
}

/** Position de lecture, émise séparément à ~4 Hz pour ne pas recomposer tout l'UI. */
data class PlaybackPosition(
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
)

sealed interface PlayerEvent {
    /** Titre ignoré après une erreur irrécupérable. */
    data class TrackSkipped(val track: Track, val error: AppError) : PlayerEvent
    /** Erreur signalée à l'utilisateur (lecture en pause). */
    data class Error(val track: Track?, val error: AppError) : PlayerEvent
    data object SleepTimerFinished : PlayerEvent
}

/**
 * Façade de contrôle du lecteur pour les ViewModels. Implémentée dans `:player` via un `MediaController`
 * connecté au service de lecture. Le player ne vit JAMAIS dans l'UI.
 */
interface PlaybackController {
    val state: StateFlow<PlayerState>
    val position: StateFlow<PlaybackPosition>
    val events: Flow<PlayerEvent>

    /**
     * Remplace la file par [tracks] et démarre à [startIndex]. [sourceId] (voir [QueueSources]) identifie
     * l'origine de la file : l'UI s'en sert pour savoir si « cette playlist » est en cours de lecture.
     */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, sourceId: String? = null)
    fun playNext(tracks: List<Track>)
    fun addToQueue(tracks: List<Track>)
    fun moveQueueItem(from: Int, to: Int)
    fun removeQueueItem(index: Int)
    fun clearQueue()
    fun skipToQueueItem(index: Int)

    fun togglePlayPause()
    fun play()
    fun pause()
    fun next()
    /** Redémarre le titre si position > 3 s, sinon titre précédent. */
    fun previous()
    fun seekTo(positionMs: Long)
    fun setShuffle(enabled: Boolean)
    fun cycleRepeatMode()
    fun setSpeed(speed: Float)
    fun toggleLikeCurrent()

    fun setSleepTimer(durationMs: Long)
    fun setSleepTimerEndOfTrack()
    fun cancelSleepTimer()
}
