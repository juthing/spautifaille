package com.spautifaille.player.history

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.spautifaille.player.QueueCommands
import com.spautifaille.player.queue.QueueSnapshots

/**
 * « Précédent » fondé sur l'historique réel ([SessionHistory]) plutôt que sur l'ordre de la file, écrit contre
 * [ExoPlayer] et testable sans service.
 */
@OptIn(UnstableApi::class)
internal object HistoryNavigation {

    /**
     * Tente de revenir au dernier titre réellement écouté. Retourne `false` (rien n'a été fait) quand le comportement
     * natif du lecteur convient : file vide, historique vide, ou, si [restartThresholdAware], position au-delà de
     * `maxSeekToPreviousPosition` (« précédent » redémarre alors le titre courant).
     *
     * Sinon, avec `target` = dernier titre écouté :
     * - c'est déjà le titre précédent de la file (ordre aléatoire compris) : on y va ;
     * - il est ailleurs dans la file (même occurrence) : on y saute ;
     * - il n'est plus dans la file (file remplacée) : on le réinsère juste avant le titre courant (et, en mode
     *   aléatoire, juste avant lui dans l'ordre de lecture, pour que « suivant » ramène au titre quitté) puis on y va.
     * Dans tous les cas la pile [history] est dépilée à la transition (voir [SessionHistory.beginBack]).
     */
    fun goBack(player: ExoPlayer, history: SessionHistory, restartThresholdAware: Boolean): Boolean {
        if (player.currentTimeline.isEmpty || player.isPlayingAd) return false
        if (restartThresholdAware && player.currentPosition > player.maxSeekToPreviousPosition) return false

        val currentIndex = player.currentMediaItemIndex
        val currentUid = SessionHistory.uidOf(player.getMediaItemAt(currentIndex))
        // Une même occurrence ne peut pas être à la fois « avant » et « maintenant » : on l'écarte.
        val target = history.snapshot().asReversed().firstOrNull { SessionHistory.uidOf(it) != currentUid } ?: return false
        val targetUid = SessionHistory.uidOf(target)
        history.beginBack(targetUid)

        val naturalIndex = player.previousMediaItemIndex
        if (naturalIndex != C.INDEX_UNSET && SessionHistory.uidOf(player.getMediaItemAt(naturalIndex)) == targetUid) {
            player.seekToDefaultPosition(naturalIndex)
            return true
        }
        val queuedIndex = (0 until player.mediaItemCount).firstOrNull { SessionHistory.uidOf(player.getMediaItemAt(it)) == targetUid }
        if (queuedIndex != null) {
            player.seekToDefaultPosition(queuedIndex)
            return true
        }
        val shuffled = player.shuffleModeEnabled
        val oldOrder = if (shuffled) QueueSnapshots.shuffleOrder(player.currentTimeline) else emptyList()
        val oldCount = player.mediaItemCount
        player.addMediaItem(currentIndex, target)
        if (shuffled && oldOrder.size == oldCount) {
            val order = QueueCommands.shuffleOrderWithInsertedBefore(oldOrder, currentIndex)
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
        }
        player.seekToDefaultPosition(currentIndex)
        return true
    }
}

/**
 * Lecteur donné à la `MediaSession` : « précédent » (bouton de l'app, notification, écran de verrouillage,
 * écouteurs, Android Auto) passe toujours par ici, côté service, donc par l'historique réel.
 */
@OptIn(UnstableApi::class)
internal class HistoryAwarePlayer(
    private val exo: ExoPlayer,
    private val history: SessionHistory,
) : ForwardingPlayer(exo) {

    override fun seekToPrevious() {
        if (HistoryNavigation.goBack(exo, history, restartThresholdAware = true)) return
        // Comportement natif : s'il change de titre, c'est un retour en arrière (le titre quitté n'est pas « écouté avant »).
        if (exo.hasPreviousMediaItem() && exo.currentPosition <= exo.maxSeekToPreviousPosition) markNativeBack()
        super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (HistoryNavigation.goBack(exo, history, restartThresholdAware = false)) return
        if (exo.hasPreviousMediaItem()) markNativeBack()
        super.seekToPreviousMediaItem()
    }

    /**
     * Sans cela, remonter la file par « précédent » empilerait chaque titre quitté et un « précédent » de plus
     * ferait des allers-retours entre deux titres.
     */
    private fun markNativeBack() {
        val index = exo.previousMediaItemIndex
        if (index != C.INDEX_UNSET) history.beginBack(SessionHistory.uidOf(exo.getMediaItemAt(index)))
    }
}
