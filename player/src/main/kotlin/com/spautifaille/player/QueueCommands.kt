package com.spautifaille.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.spautifaille.player.queue.QueueSnapshots

/**
 * Manipulations de file, écrites contre l'interface [Player] (donc valables pour le `MediaController` côté UI comme
 * pour l'`ExoPlayer` du service) et testables sans service.
 */
@OptIn(UnstableApi::class)
internal object QueueCommands {

    /**
     * Remplace la file par [items].
     *
     * - [shuffle] `true` : active le mode aléatoire. Sans index explicite ([startIndex] <= 0), la lecture démarre au
     *   premier titre de l'**ordre mélangé** (`setMediaItems(items, resetPosition = true)`), sinon les titres placés
     *   avant l'index dans l'ordre mélangé ne seraient jamais joués.
     * - [shuffle] `false` : le mode aléatoire choisi par l'utilisateur est laissé tel quel (toucher un titre dans une
     *   liste ne doit pas le désactiver silencieusement) ; la lecture démarre à [startIndex].
     *
     * N'appelle ni `prepare()` ni `play()`.
     */
    fun setQueue(player: Player, items: List<MediaItem>, startIndex: Int, shuffle: Boolean) {
        if (items.isEmpty()) return
        if (shuffle) player.shuffleModeEnabled = true
        if (shuffle && startIndex <= 0) {
            player.setMediaItems(items, /* resetPosition = */ true)
        } else {
            player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), C.TIME_UNSET)
        }
    }

    /**
     * Insère [items] juste après le titre courant, y compris en mode aléatoire (où ExoPlayer placerait sinon les
     * nouveaux titres à des positions aléatoires de l'ordre mélangé).
     */
    fun playNext(player: ExoPlayer, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val count = player.mediaItemCount
        if (count == 0) {
            player.addMediaItems(items)
            return
        }
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val shuffled = player.shuffleModeEnabled
        val oldOrder = if (shuffled) QueueSnapshots.shuffleOrder(player.currentTimeline) else emptyList()
        player.addMediaItems(current + 1, items)
        if (shuffled && oldOrder.size == count) {
            val order = shuffleOrderWithInsertedAfter(oldOrder, current, items.size)
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
        }
    }

    /**
     * Nouvel ordre de lecture aléatoire après insertion de [count] fenêtres à l'index `currentIndex + 1` :
     * les anciens indices >= `currentIndex + 1` sont décalés de [count], et les nouveaux indices
     * (`currentIndex + 1 ..`) sont placés juste après [currentIndex] dans l'ordre de lecture.
     *
     * @param order ordre de lecture **avant** insertion (indices de fenêtres, dans l'ordre de lecture).
     */
    fun shuffleOrderWithInsertedAfter(order: List<Int>, currentIndex: Int, count: Int): IntArray {
        val firstNew = currentIndex + 1
        val shifted = order.map { if (it >= firstNew) it + count else it }
        val insertAt = shifted.indexOf(currentIndex) + 1 // 0 si le titre courant est absent (défensif)
        val result = ArrayList<Int>(shifted.size + count)
        result.addAll(shifted.subList(0, insertAt))
        for (i in 0 until count) result.add(firstNew + i)
        result.addAll(shifted.subList(insertAt, shifted.size))
        return result.toIntArray()
    }

    /**
     * Nouvel ordre de lecture aléatoire après insertion d'**une** fenêtre à l'index [currentIndex] (donc juste avant
     * le titre courant, qui passe à `currentIndex + 1`) : les anciens indices >= [currentIndex] sont décalés de 1, et
     * la nouvelle fenêtre est placée immédiatement avant le titre courant dans l'ordre de lecture.
     *
     * @param order ordre de lecture **avant** insertion (indices de fenêtres, dans l'ordre de lecture).
     */
    fun shuffleOrderWithInsertedBefore(order: List<Int>, currentIndex: Int): IntArray {
        val shifted = order.map { if (it >= currentIndex) it + 1 else it }
        val insertAt = shifted.indexOf(currentIndex + 1).let { if (it < 0) shifted.size else it } // défensif
        val result = ArrayList<Int>(shifted.size + 1)
        result.addAll(shifted.subList(0, insertAt))
        result.add(currentIndex)
        result.addAll(shifted.subList(insertAt, shifted.size))
        return result.toIntArray()
    }
}
