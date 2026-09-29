package com.spautifaille.player.queue

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.spautifaille.domain.player.RepeatMode
import com.spautifaille.domain.repository.QueueSnapshot
import com.spautifaille.domain.repository.QueueStateStore
import com.spautifaille.player.MediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun Int.toDomainRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    else -> RepeatMode.OFF
}

fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

/** Conversion état du lecteur ⇄ [QueueSnapshot]. */
@OptIn(UnstableApi::class)
object QueueSnapshots {

    /** Instantané de la file du lecteur, ou null si elle est vide. */
    fun capture(player: Player): QueueSnapshot? {
        val count = player.mediaItemCount
        if (count == 0) return null
        val items = List(count) { player.getMediaItemAt(it) }
        return QueueSnapshot(
            tracks = items.map(MediaItemMapper::toTrack),
            currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1),
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode.toDomainRepeatMode(),
            shuffleOrder = if (player.shuffleModeEnabled) shuffleOrder(player.currentTimeline) else emptyList(),
        )
    }

    /** Ordre de lecture en mode aléatoire : indices de fenêtres dans l'ordre de lecture. */
    fun shuffleOrder(timeline: Timeline): List<Int> {
        if (timeline.isEmpty) return emptyList()
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    /** Applique [snapshot] au lecteur sans le préparer (pas d'accès réseau tant que l'utilisateur ne lance pas la lecture). */
    fun restore(player: ExoPlayer, snapshot: QueueSnapshot) {
        val items: List<MediaItem> = snapshot.tracks.map { MediaItemMapper.toMediaItem(it) }
        if (items.isEmpty()) return
        val index = snapshot.currentIndex.coerceIn(0, items.lastIndex)
        player.repeatMode = snapshot.repeatMode.toPlayerRepeatMode()
        player.shuffleModeEnabled = snapshot.shuffleEnabled
        player.setMediaItems(items, index, snapshot.positionMs.coerceAtLeast(0))
        if (snapshot.shuffleEnabled && isPermutation(snapshot.shuffleOrder, items.size)) {
            player.shuffleOrder = ShuffleOrder.DefaultShuffleOrder(snapshot.shuffleOrder.toIntArray(), System.nanoTime())
        }
    }

    internal fun isPermutation(order: List<Int>, size: Int): Boolean =
        order.size == size && order.toSet() == (0 until size).toSet()
}

/**
 * Ce qui, hors index et position, décide de réécrire la file complète : identifiants des titres (dans l'ordre),
 * mode aléatoire + ordre de lecture, mode de répétition.
 */
internal data class QueueSignature(
    val mediaIds: List<String>,
    val shuffleEnabled: Boolean,
    val shuffleOrder: List<Int>,
    val repeatMode: Int,
) {
    companion object {
        /** Signature de la file du lecteur, ou null si elle est vide. */
        fun of(player: Player): QueueSignature? {
            val count = player.mediaItemCount
            if (count == 0) return null
            val shuffle = player.shuffleModeEnabled
            return QueueSignature(
                mediaIds = List(count) { player.getMediaItemAt(it).mediaId },
                shuffleEnabled = shuffle,
                shuffleOrder = if (shuffle) QueueSnapshots.shuffleOrder(player.currentTimeline) else emptyList(),
                repeatMode = player.repeatMode,
            )
        }
    }
}

/**
 * Sauvegarde la file via [QueueStateStore] :
 * - file complète, avec anti-rebond de [debounceMs], sur changement de timeline / d'élément / de shuffle / de repeat
 *   (seuls l'index et la position sont réécrits si la [QueueSignature] n'a pas changé) ;
 * - position toutes les [positionIntervalMs] pendant la lecture, et immédiatement à la pause / l'arrêt.
 *
 * Les lectures du lecteur se font sur [scope] (thread principal) ; les écritures partent sur [writeScope]
 * (portée applicative) pour survivre à la destruction du service.
 */
class QueuePersister(
    private val player: Player,
    private val store: QueueStateStore,
    private val scope: CoroutineScope,
    private val writeScope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val positionIntervalMs: Long = DEFAULT_POSITION_INTERVAL_MS,
) : Player.Listener {

    private var saveJob: Job? = null

    /** Signature de la dernière file écrite avec succès ; écrite depuis [writeScope], d'où `@Volatile`. */
    @Volatile
    private var lastSaved: QueueSignature? = null
    private var tickerJob: Job? = null

    /**
     * Tant que la restauration initiale n'est pas terminée, une file vide ne doit pas écraser la file sauvegardée.
     * [arm] est appelé par le service à la fin de la restauration ; une file non vide arme aussi le persister.
     */
    private var armed = false

    fun arm() {
        armed = true
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = scheduleSave()
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = scheduleSave()
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleSave()
    override fun onRepeatModeChanged(repeatMode: Int) = scheduleSave()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        tickerJob?.cancel()
        if (isPlaying) {
            tickerJob = scope.launch {
                while (true) {
                    delay(positionIntervalMs)
                    savePositionNow()
                }
            }
        } else {
            savePositionNow()
        }
    }

    /** Écrit immédiatement la file complète (fermeture de l'application, destruction du service). */
    fun flushNow() {
        saveJob?.cancel()
        saveJob = null
        writeSnapshot()
    }

    fun release() {
        saveJob?.cancel()
        tickerJob?.cancel()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            writeSnapshot()
        }
    }

    private fun writeSnapshot() {
        val signature = QueueSignature.of(player)
        if (signature != null) armed = true
        if (!armed) return
        if (signature != null && signature == lastSaved) {
            // File, ordre aléatoire et répétition inchangés : inutile de réécrire toute la file.
            savePositionNow()
            return
        }
        val snapshot = QueueSnapshots.capture(player)
        writeScope.launch {
            withContext(NonCancellable) {
                runCatching { if (snapshot == null) store.clear() else store.save(snapshot) }
                    .onSuccess { lastSaved = signature }
            }
        }
    }

    private fun savePositionNow() {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0)
        writeScope.launch {
            withContext(NonCancellable) { runCatching { store.savePosition(index, position) } }
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
        const val DEFAULT_POSITION_INTERVAL_MS = 10_000L
    }
}
