package com.spautifaille.player.error

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.player.PlayerEvent
import com.spautifaille.player.MediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applique [PlaybackErrorPolicy] aux erreurs du lecteur (thread principal) :
 * - re-résolution (403/410...) : invalide l'URL puis re-prépare à la même position, 2 fois max par titre ;
 * - réseau : backoff exponentiel 1 s -> 30 s ; hors connexion, attend le retour du réseau sans compter de tentative ;
 * - irrécupérable : saute au titre suivant (et prévient), ou s'arrête en pause s'il n'y a rien à enchaîner.
 */
class PlaybackErrorHandler(
    private val player: Player,
    private val scope: CoroutineScope,
    private val connectivity: ConnectivityObserver,
    private val invalidateStream: (videoId: String) -> Unit,
    private val emit: (PlayerEvent) -> Unit,
    private val clock: () -> Long,
) : Player.Listener {

    private var trackedKey: String? = null
    private var reResolveAttempts = 0
    private var networkAttempts = 0
    private var consecutiveSkips = 0
    private var lastErrorAtMs: Long? = null

    private var retryJob: Job? = null
    private var networkWatch: AutoCloseable? = null

    override fun onPlayerError(error: PlaybackException) {
        val item = player.currentMediaItem
        syncTrackedItem(item)
        lastErrorAtMs = clock()
        Log.w(TAG, "Playback error ${error.errorCodeName} on ${item?.mediaId}", error)

        val hasNext = player.hasNextMediaItem() &&
            consecutiveSkips < minOf(player.mediaItemCount, MAX_CONSECUTIVE_SKIPS)
        val action = PlaybackErrorPolicy.decide(error, ErrorContext(reResolveAttempts, networkAttempts, hasNext))
        cancelPendingRetry()

        when (action) {
            ErrorAction.ReResolve -> {
                reResolveAttempts++
                item?.mediaId?.let(invalidateStream)
                retryPlayback()
            }
            is ErrorAction.Retry -> scheduleRetry(action.delayMs)
            is ErrorAction.Skip -> {
                emit(PlayerEvent.TrackSkipped(trackOf(item), action.error))
                consecutiveSkips++
                skipToNext()
            }
            is ErrorAction.Fail -> {
                emit(PlayerEvent.Error(item?.let(MediaItemMapper::toTrack), action.error))
                player.pause()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncTrackedItem(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) return
        consecutiveSkips = 0
        // Une lecture stable depuis un moment : les compteurs de tentatives repartent de zéro.
        if (lastErrorAtMs?.let { clock() - it > STABLE_PLAYBACK_MS } != false) {
            reResolveAttempts = 0
            networkAttempts = 0
        }
    }

    fun release() {
        cancelPendingRetry()
    }

    private fun scheduleRetry(delayMs: Long) {
        if (!connectivity.isOnline()) {
            // Hors connexion : pas de tentative comptée, on reprend dès qu'un réseau revient.
            networkWatch = connectivity.onAvailable {
                cancelPendingRetry()
                retryPlayback()
            }
            return
        }
        networkAttempts++
        retryJob = scope.launch {
            delay(delayMs)
            retryPlayback()
        }
    }

    private fun retryPlayback() {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        player.seekTo(index, position)
        player.prepare()
    }

    private fun skipToNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        } else {
            player.pause()
        }
    }

    private fun syncTrackedItem(item: MediaItem?) {
        val key = item?.let { MediaItemMapper.queueUid(it) ?: it.mediaId }
        if (key == trackedKey) return
        trackedKey = key
        reResolveAttempts = 0
        networkAttempts = 0
        cancelPendingRetry()
    }

    private fun cancelPendingRetry() {
        retryJob?.cancel()
        retryJob = null
        networkWatch?.close()
        networkWatch = null
    }

    private fun trackOf(item: MediaItem?) =
        item?.let(MediaItemMapper::toTrack) ?: com.spautifaille.domain.model.Track("", "", "")

    private companion object {
        const val TAG = "PlaybackErrorHandler"
        const val MAX_CONSECUTIVE_SKIPS = 10
        const val STABLE_PLAYBACK_MS = 30_000L
    }
}
