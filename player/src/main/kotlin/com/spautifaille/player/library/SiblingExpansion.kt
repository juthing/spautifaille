package com.spautifaille.player.library

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.spautifaille.domain.model.Track
import com.spautifaille.player.MediaItemMapper

/**
 * Android Auto envoie uniquement le titre touché. Quand celui-ci porte l'identifiant de son dossier d'origine
 * ([MediaItemMapper.EXTRA_PARENT_ID]), la file devient tout le dossier, positionnée sur ce titre.
 */
@OptIn(UnstableApi::class)
internal object SiblingExpansion {

    /**
     * @return la file étendue, ou null si l'expansion ne s'applique pas (plusieurs items, pas de dossier d'origine,
     *   dossier introuvable ou titre absent du dossier) : l'appelant garde alors le comportement par défaut.
     */
    suspend fun expand(
        items: List<MediaItem>,
        startPositionMs: Long,
        tracksFor: suspend (parentId: String) -> List<Track>?,
    ): MediaSession.MediaItemsWithStartPosition? {
        val tapped = items.singleOrNull() ?: return null
        val parentId = MediaItemMapper.parentId(tapped) ?: return null
        val siblings = tracksFor(parentId)?.takeIf { it.isNotEmpty() } ?: return null
        val index = siblings.indexOfFirst { it.id == tapped.mediaId }
        if (index < 0) return null
        return MediaSession.MediaItemsWithStartPosition(
            siblings.map { MediaItemMapper.toMediaItem(it) },
            index,
            startPositionMs,
        )
    }
}
