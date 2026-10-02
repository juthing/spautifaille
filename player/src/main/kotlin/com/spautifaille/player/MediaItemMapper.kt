package com.spautifaille.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spautifaille.domain.model.Track
import com.spautifaille.player.datasource.TrackUri
import java.util.UUID

/**
 * Conversion [Track] ⇄ [MediaItem].
 *
 * - `mediaId` = identifiant vidéo, `uri` = URI stable `spautifaille://track/<videoId>` (jamais d'URL de flux :
 *   elle est résolue au dernier moment par la chaîne de data sources).
 * - Les extras des métadonnées portent l'URL de la chaîne et un `uid` unique par occurrence dans la file
 *   (un même titre peut apparaître plusieurs fois).
 */
object MediaItemMapper {
    const val EXTRA_ARTIST_URL = "com.spautifaille.player.ARTIST_URL"
    const val EXTRA_QUEUE_UID = "com.spautifaille.player.QUEUE_UID"

    /** Identifiant du dossier de navigation (likés, playlist, récents) d'où provient un titre de l'arbre Android Auto. */
    const val EXTRA_PARENT_ID = "com.spautifaille.player.PARENT_ID"

    /** Origine de la file (voir `QueueSources`) portée par chaque titre posé par `PlaybackController.play`. */
    const val EXTRA_SOURCE_ID = "com.spautifaille.player.SOURCE_ID"

    fun newUid(): String = UUID.randomUUID().toString()

    fun toMediaItem(
        track: Track,
        uid: String = newUid(),
        parentId: String? = null,
        sourceId: String? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            parentId?.let { putString(EXTRA_PARENT_ID, it) }
            sourceId?.let { putString(EXTRA_SOURCE_ID, it) }
            track.artistUrl?.let { putString(EXTRA_ARTIST_URL, it) }
            putString(EXTRA_QUEUE_UID, uid)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.thumbnailUrl?.let(Uri::parse))
            .setDurationMs(track.durationMs)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(TrackUri.build(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    fun toMediaItems(tracks: List<Track>, sourceId: String? = null): List<MediaItem> =
        tracks.map { toMediaItem(it, sourceId = sourceId) }

    fun toTrack(item: MediaItem): Track {
        val metadata = item.mediaMetadata
        return Track(
            id = item.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = (metadata.artist ?: metadata.subtitle)?.toString().orEmpty(),
            artistUrl = metadata.extras?.getString(EXTRA_ARTIST_URL),
            album = metadata.albumTitle?.toString(),
            durationMs = metadata.durationMs?.takeIf { it != C.TIME_UNSET && it > 0 },
            thumbnailUrl = metadata.artworkUri?.toString(),
        )
    }

    /** Identifiant unique de l'occurrence dans la file, ou null pour un item créé hors de ce mapper. */
    fun queueUid(item: MediaItem): String? = item.mediaMetadata.extras?.getString(EXTRA_QUEUE_UID)

    /** Origine de la file (`QueueSources`) de ce titre, ou null. */
    fun sourceId(item: MediaItem): String? = item.mediaMetadata.extras?.getString(EXTRA_SOURCE_ID)

    /** Dossier de navigation d'origine du titre (arbre de bibliothèque), ou null. */
    fun parentId(item: MediaItem): String? = item.mediaMetadata.extras?.getString(EXTRA_PARENT_ID)

    /** Garantit la présence d'un `uid` (items reçus de contrôleurs externes). */
    fun ensureUid(item: MediaItem): MediaItem {
        if (queueUid(item) != null) return item
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putString(EXTRA_QUEUE_UID, newUid()) }
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }
}
