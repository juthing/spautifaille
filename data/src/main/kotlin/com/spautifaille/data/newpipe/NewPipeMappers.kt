package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Conversions NewPipe -> modèles du domaine. Fonctions pures, sans accès réseau. */
internal object NewPipeMappers {
    private const val MAX_THUMBNAIL_WIDTH = 720
    private const val TOPIC_SUFFIX = " - Topic"

    /** "Artiste - Topic" (chaînes auto-générées par YouTube Music) -> "Artiste". */
    fun cleanArtistName(name: String?): String =
        name.orEmpty().trim().removeSuffix(TOPIC_SUFFIX).trim()

    /** Identifiant vidéo (11 caractères) extrait d'une URL, `null` si l'URL n'est pas celle d'une vidéo. */
    fun videoIdOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            YoutubeStreamLinkHandlerFactory.getInstance().getId(url)
        } catch (_: ParsingException) {
            null
        }
    }

    /**
     * Meilleure vignette : la plus large ne dépassant pas ~720 px ; si toutes sont plus grandes, la plus
     * petite ; si aucune largeur n'est connue, la plus haute <= 720 px de haut, sinon la première.
     */
    fun pickThumbnail(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val withWidth = images.filter { it.width != Image.WIDTH_UNKNOWN && it.width > 0 }
        if (withWidth.isNotEmpty()) {
            val fitting = withWidth.filter { it.width <= MAX_THUMBNAIL_WIDTH }
            val chosen = if (fitting.isNotEmpty()) fitting.maxBy { it.width } else withWidth.minBy { it.width }
            return chosen.url
        }
        val withHeight = images.filter { it.height != Image.HEIGHT_UNKNOWN && it.height > 0 }
        if (withHeight.isNotEmpty()) {
            val fitting = withHeight.filter { it.height <= MAX_THUMBNAIL_WIDTH }
            val chosen = if (fitting.isNotEmpty()) fitting.maxBy { it.height } else withHeight.minBy { it.height }
            return chosen.url
        }
        return images.first().url
    }

    /** Plus grande image (bannières). */
    fun pickLargest(images: List<Image>?): String? =
        images?.maxByOrNull { maxOf(it.width, it.height) }?.url

    private fun isLive(type: StreamType?): Boolean =
        type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM

    fun toTrack(item: StreamInfoItem): Track? {
        if (isLive(item.streamType)) return null
        val id = videoIdOf(item.url) ?: return null
        return Track(
            id = id,
            title = item.name.orEmpty(),
            artist = cleanArtistName(item.uploaderName),
            artistUrl = item.uploaderUrl?.takeIf { it.isNotBlank() },
            album = null,
            durationMs = item.duration.takeIf { it > 0 }?.times(1000),
            thumbnailUrl = pickThumbnail(item.thumbnails),
        )
    }

    fun toTrack(info: StreamInfo, videoId: String): Track = Track(
        id = videoId,
        title = info.name.orEmpty(),
        artist = cleanArtistName(info.uploaderName),
        artistUrl = info.uploaderUrl?.takeIf { it.isNotBlank() },
        album = null,
        durationMs = info.duration.takeIf { it > 0 }?.times(1000),
        thumbnailUrl = pickThumbnail(info.thumbnails),
    )

    fun toPlaylist(item: PlaylistInfoItem): RemotePlaylist = RemotePlaylist(
        url = item.url,
        name = item.name.orEmpty(),
        uploader = item.uploaderName?.let(::cleanArtistName)?.takeIf { it.isNotEmpty() },
        thumbnailUrl = pickThumbnail(item.thumbnails),
        trackCount = item.streamCount.takeIf { it >= 0 },
    )

    fun toArtist(item: ChannelInfoItem): Artist = Artist(
        url = item.url,
        name = cleanArtistName(item.name),
        avatarUrl = pickThumbnail(item.thumbnails),
        subscriberCount = item.subscriberCount.takeIf { it >= 0 },
    )

    fun toSearchResult(item: InfoItem): SearchResult? = when (item) {
        is StreamInfoItem -> toTrack(item)?.let { SearchResult.TrackResult(it) }
        is PlaylistInfoItem -> SearchResult.PlaylistResult(toPlaylist(item))
        is ChannelInfoItem -> SearchResult.ArtistResult(toArtist(item))
        else -> null
    }

    fun toTracks(items: List<InfoItem>?): List<Track> =
        items.orEmpty().filterIsInstance<StreamInfoItem>().mapNotNull(::toTrack)
}
