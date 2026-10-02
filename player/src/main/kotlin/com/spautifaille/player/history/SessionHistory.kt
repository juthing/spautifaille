package com.spautifaille.player.history

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.spautifaille.player.MediaItemMapper

/**
 * Pile (bornée) des titres **réellement écoutés** pendant la session du service, du plus ancien au plus récent ;
 * le titre courant n'en fait pas partie. Elle sert à « précédent » (voir [HistoryNavigation]) : remplacer la file
 * (lancer une playlist) ne doit pas faire oublier ce qu'on écoutait juste avant.
 *
 * Alimentée par [onMediaItemTransition] (à enregistrer sur le lecteur). Les transitions provoquées par « précédent »
 * lui-même, annoncées par [beginBack], dépilent au lieu d'empiler. Rien n'est persisté : uniquement des `MediaItem`
 * de métadonnées (jamais d'URL de flux), perdus à la fin du process.
 *
 * Accès sur le thread du lecteur uniquement.
 *
 * @param onChanged appelé après chaque modification (publication de l'état « un précédent existe »).
 */
internal class SessionHistory(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onChanged: () -> Unit = {},
) : Player.Listener {

    private val stack = ArrayDeque<MediaItem>()
    private var current: MediaItem? = null
    private var backTargetUid: String? = null

    val size: Int get() = stack.size
    val isEmpty: Boolean get() = stack.isEmpty()

    /** Dernier titre écouté avant le titre courant, ou null. */
    fun peek(): MediaItem? = stack.lastOrNull()

    /** Titres écoutés, du plus ancien au plus récent (tests, diagnostics). */
    fun snapshot(): List<MediaItem> = stack.toList()

    /** Initialise le titre courant sans rien empiler (démarrage du service, file restaurée). */
    fun reset(currentItem: MediaItem?) {
        current = currentItem
        backTargetUid = null
    }

    /**
     * Annonce que la prochaine transition vers l'occurrence [uid] est un retour en arrière demandé par « précédent » :
     * elle dépilera [uid] au lieu d'empiler le titre quitté. Oublié à la première transition, quelle qu'elle soit.
     */
    fun beginBack(uid: String) {
        backTargetUid = uid
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val previous = current
        current = mediaItem
        val back = backTargetUid
        backTargetUid = null

        if (back != null && mediaItem != null && uidOf(mediaItem) == back) {
            if (stack.lastOrNull()?.let(::uidOf) == back) {
                stack.removeLast()
                onChanged()
            }
            return
        }
        if (previous == null) return
        // Boucle du même titre (répétition d'un titre) ou même titre relancé : rien d'écouté « avant ».
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        if (mediaItem != null && mediaItem.mediaId == previous.mediaId) return
        if (stack.lastOrNull()?.mediaId == previous.mediaId) return

        stack.addLast(previous)
        while (stack.size > capacity) stack.removeFirst()
        onChanged()
    }

    companion object {
        const val DEFAULT_CAPACITY = 100

        /** Identité d'une occurrence dans la file : l'`uid` du mapper, à défaut l'identifiant du titre. */
        fun uidOf(item: MediaItem): String = MediaItemMapper.queueUid(item) ?: item.mediaId
    }
}
