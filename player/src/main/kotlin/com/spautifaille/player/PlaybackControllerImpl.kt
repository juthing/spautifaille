package com.spautifaille.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.spautifaille.domain.di.ApplicationScope
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerEvent
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.QueueItem
import com.spautifaille.domain.player.RepeatMode
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.player.queue.toDomainRepeatMode
import com.spautifaille.player.queue.toPlayerRepeatMode
import com.spautifaille.player.sleep.SleepTimerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackController] adossé à un [MediaController] connecté à [PlaybackService].
 *
 * - Connexion paresseuse (première commande ou premier accès à [state]/[position]/[events]), reconnexion automatique
 *   si le service est tué.
 * - Les commandes émises avant la fin de la connexion sont mises en file et rejouées à la connexion.
 * - Tout accès au `MediaController` se fait sur le thread principal.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlaybackControllerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : PlaybackController {

    private val main = Dispatchers.Main.immediate

    private val _state = MutableStateFlow(PlayerState())
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val positionInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Accès thread principal uniquement.
    private var controller: MediaController? = null
    private val pendingCommands = ArrayDeque<(MediaController) -> Unit>()
    private var queue: List<QueueItem> = emptyList()
    private val connectStarted = AtomicBoolean(false)
    private val readOnlyState = _state.asStateFlow()
    private val readOnlyEvents = _events.asSharedFlow()

    override val state: StateFlow<PlayerState>
        get() {
            ensureConnected()
            return readOnlyState
        }

    override val events: Flow<PlayerEvent>
        get() {
            ensureConnected()
            return readOnlyEvents
        }

    /**
     * Position ~4 Hz, émise seulement tant qu'il y a des collecteurs, et uniquement pendant la lecture
     * (hors lecture : une valeur à chaque changement d'état / seek).
     */
    override val position: StateFlow<PlaybackPosition> = positionInvalidations
        .onStart {
            ensureConnected()
            emit(Unit)
        }
        .flatMapLatest {
            flow {
                emit(readPosition())
                while (controller?.isPlaying == true) {
                    delay(POSITION_TICK_MS)
                    emit(readPosition())
                }
            }
        }
        .flowOn(main)
        .stateIn(appScope, SharingStarted.WhileSubscribed(POSITION_STOP_TIMEOUT_MS), PlaybackPosition())

    // --- Connexion ------------------------------------------------------------------------------------------------

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.w(TAG, "MediaController disconnected; reconnecting")
            handleDisconnected(controller)
        }

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            refreshState(controller, rebuildQueue = false)
        }

        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == SessionContract.ACTION_EVENT) {
                SessionContract.decodeEvent(args)?.let(_events::tryEmit)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val rebuildQueue = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_METADATA_CHANGED,
            )
            controller?.let { refreshState(it, rebuildQueue) }
            positionInvalidations.tryEmit(Unit)
        }
    }

    private fun ensureConnected() {
        if (connectStarted.compareAndSet(false, true)) appScope.launch(main) { connectLoop() }
    }

    private suspend fun connectLoop() {
        var backoffMs = INITIAL_RECONNECT_MS
        while (true) {
            try {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val connected = MediaController.Builder(context, token)
                    .setListener(controllerListener)
                    .buildAsync()
                    .await()
                onConnected(connected)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "MediaController connection failed, retrying in ${backoffMs}ms", e)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RECONNECT_MS)
            }
        }
    }

    private fun onConnected(connected: MediaController) {
        controller = connected
        connected.addListener(playerListener)
        refreshState(connected, rebuildQueue = true)
        while (pendingCommands.isNotEmpty()) pendingCommands.poll()?.invoke(connected)
        positionInvalidations.tryEmit(Unit)
    }

    private fun handleDisconnected(disconnected: MediaController) {
        if (controller !== disconnected) return
        controller = null
        queue = emptyList()
        disconnected.removeListener(playerListener)
        disconnected.release()
        _state.value = PlayerState(isConnected = false)
        positionInvalidations.tryEmit(Unit)
        connectStarted.set(false)
        ensureConnected()
    }

    /** Exécute [block] sur le thread principal dès que le contrôleur est connecté. */
    private fun withController(block: (MediaController) -> Unit) {
        appScope.launch(main) {
            val current = controller
            if (current != null) {
                block(current)
            } else {
                pendingCommands.add(block)
                ensureConnected()
            }
        }
    }

    // --- État -----------------------------------------------------------------------------------------------------

    private fun refreshState(c: MediaController, rebuildQueue: Boolean) {
        if (rebuildQueue) {
            queue = List(c.mediaItemCount) { index ->
                val item = c.getMediaItemAt(index)
                QueueItem(MediaItemMapper.queueUid(item) ?: "${item.mediaId}#$index", MediaItemMapper.toTrack(item))
            }
        }
        val index = if (c.mediaItemCount == 0) -1 else c.currentMediaItemIndex
        val currentTrack = c.currentMediaItem?.let(MediaItemMapper::toTrack)
        val published = SessionContract.decodeExtras(c.sessionExtras)
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: currentTrack?.durationMs ?: 0L
        _state.value = PlayerState(
            isConnected = true,
            currentTrack = currentTrack,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            playWhenReady = c.playWhenReady,
            durationMs = duration,
            queue = queue,
            currentIndex = index,
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = c.repeatMode.toDomainRepeatMode(),
            speed = c.playbackParameters.speed,
            sleepTimer = when (val timer = published.sleepTimer) {
                SleepTimerState.Off -> SleepTimer.Off
                SleepTimerState.EndOfTrack -> SleepTimer.EndOfTrack
                is SleepTimerState.At -> SleepTimer.At(
                    endsAtElapsedMs = timer.endsAtElapsedMs,
                    remainingMs = (timer.endsAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                )
            },
            isCurrentLiked = published.liked && currentTrack != null,
            isCurrentOffline = published.offline && currentTrack != null,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem() || published.hasHistory,
        )
    }

    private fun readPosition(): PlaybackPosition {
        val c = controller ?: return PlaybackPosition()
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: c.currentMediaItem?.let(MediaItemMapper::toTrack)?.durationMs
            ?: 0L
        return PlaybackPosition(
            positionMs = c.currentPosition.coerceAtLeast(0),
            bufferedMs = c.bufferedPosition.coerceAtLeast(0),
            durationMs = duration,
        )
    }

    // --- Commandes ------------------------------------------------------------------------------------------------

    override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        val items = MediaItemMapper.toMediaItems(tracks)
        withController { c ->
            QueueCommands.setQueue(c, items, startIndex, shuffle)
            c.prepare()
            c.play()
        }
    }

    override fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withController { c ->
            if (c.mediaItemCount == 0) {
                c.setMediaItems(MediaItemMapper.toMediaItems(tracks))
                c.prepare()
                c.play()
            } else {
                // Commande dédiée : en mode aléatoire, addMediaItems placerait les titres à des positions
                // aléatoires de l'ordre mélangé ; le service recalcule l'ordre pour les mettre juste après le courant.
                c.sendCustomCommand(SessionContract.playNextCommand, SessionContract.playNextArgs(tracks))
            }
        }
    }

    override fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = MediaItemMapper.toMediaItems(tracks)
        withController { c ->
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(items)
            if (wasEmpty) c.prepare()
        }
    }

    override fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount && from != to) c.moveMediaItem(from, to)
    }

    override fun removeQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    override fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    override fun skipToQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            Util.handlePlayButtonAction(c)
        }
    }

    override fun togglePlayPause() = withController { c ->
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE) c.pause()
        else Util.handlePlayButtonAction(c)
    }

    override fun play() = withController { c -> Util.handlePlayButtonAction(c) }

    override fun pause() = withController { c -> c.pause() }

    override fun next() = withController { c -> c.seekToNextMediaItem() }

    override fun previous() = withController { c -> c.seekToPrevious() }

    override fun seekTo(positionMs: Long) = withController { c -> c.seekTo(positionMs.coerceAtLeast(0)) }

    override fun setShuffle(enabled: Boolean) = withController { c -> c.shuffleModeEnabled = enabled }

    override fun cycleRepeatMode() = withController { c ->
        c.repeatMode = when (c.repeatMode.toDomainRepeatMode()) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }.toPlayerRepeatMode()
    }

    override fun setSpeed(speed: Float) = withController { c -> c.setPlaybackSpeed(speed) }

    override fun toggleLikeCurrent() = withController { c ->
        c.sendCustomCommand(SessionContract.toggleLikeCommand, Bundle.EMPTY)
    }

    override fun setSleepTimer(durationMs: Long) = withController { c ->
        c.sendCustomCommand(SessionContract.setSleepTimerCommand, SessionContract.sleepTimerArgs(durationMs))
    }

    override fun setSleepTimerEndOfTrack() = withController { c ->
        c.sendCustomCommand(SessionContract.setSleepTimerCommand, SessionContract.sleepTimerEndOfTrackArgs())
    }

    override fun cancelSleepTimer() = withController { c ->
        c.sendCustomCommand(SessionContract.cancelSleepTimerCommand, Bundle.EMPTY)
    }

    private companion object {
        const val TAG = "PlaybackController"
        const val POSITION_TICK_MS = 250L
        const val POSITION_STOP_TIMEOUT_MS = 2_000L
        const val INITIAL_RECONNECT_MS = 1_000L
        const val MAX_RECONNECT_MS = 30_000L
    }
}
