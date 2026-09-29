package com.spautifaille.player.library

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.domain.repository.TrackCache
import com.spautifaille.player.MediaItemMapper
import com.spautifaille.player.R
import kotlinx.coroutines.flow.first

/**
 * Arbre de navigation exposé aux clients `MediaBrowser` (Android Auto, Assistant, Wear, écran de verrouillage) :
 *
 * ```
 * root
 *  ├─ liked        Titres likés      (titres jouables)
 *  ├─ playlists    Playlists         (playlist/<id> -> titres jouables)
 *  └─ recent       Récemment écoutés (titres jouables)
 * ```
 * Les titres gardent `mediaId = videoId` ; les nœuds de navigation ont un id non vidéo (voir [isContainerId]).
 * Aucune lecture de flux ici : les titres ne portent que leur URI stable.
 */
class LibraryBrowseTree(
    private val context: Context,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val streams: StreamRepository,
    private val trackCache: TrackCache,
) {
    val rootItem: MediaItem by lazy {
        container(ROOT_ID, context.getString(R.string.player_browse_root), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    }

    private val recentItem: MediaItem by lazy {
        container(RECENT_ID, context.getString(R.string.player_browse_recent), MediaMetadata.MEDIA_TYPE_PLAYLIST)
    }

    /** Racine « récent » : demandée par les clients qui veulent reprendre la dernière écoute (`LibraryParams.isRecent`). */
    val recentRootItem: MediaItem get() = recentItem

    fun isContainerId(id: String): Boolean =
        id == ROOT_ID || id == LIKED_ID || id == PLAYLISTS_ID || id == RECENT_ID || id.startsWith(PLAYLIST_PREFIX)

    suspend fun getItem(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> rootItem
        mediaId == LIKED_ID -> container(LIKED_ID, context.getString(R.string.player_browse_liked), MediaMetadata.MEDIA_TYPE_PLAYLIST)
        mediaId == PLAYLISTS_ID -> container(PLAYLISTS_ID, context.getString(R.string.player_browse_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        mediaId == RECENT_ID -> recentItem
        mediaId.startsWith(PLAYLIST_PREFIX) -> {
            val id = mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
            id?.let { playlists.observePlaylists().first().firstOrNull { p -> p.id == it } }?.let(::playlistItem)
        }
        else -> loadTrack(mediaId)?.let { MediaItemMapper.toMediaItem(it) }
    }

    /** Enfants d'un nœud, ou null si [parentId] est inconnu. */
    suspend fun getChildren(parentId: String): List<MediaItem>? = when {
        parentId == ROOT_ID -> listOf(
            getItem(LIKED_ID)!!,
            getItem(PLAYLISTS_ID)!!,
            recentItem,
        )
        parentId == PLAYLISTS_ID -> playlists.observePlaylists().first().filterNot { it.isSystem }.map(::playlistItem)
        else -> tracksFor(parentId)?.map { MediaItemMapper.toMediaItem(it, parentId = parentId) }
    }

    /** Titres contenus dans un nœud jouable (likés, playlist, récents), ou null si [mediaId] n'en est pas un. */
    suspend fun tracksFor(mediaId: String): List<Track>? = when {
        mediaId == LIKED_ID -> playlistTracks(Playlist.LIKED_ID)
        mediaId == RECENT_ID -> library.observeHistory(RECENT_LIMIT).first()
            .map { it.track }
            .distinctBy { it.id }
        mediaId.startsWith(PLAYLIST_PREFIX) -> mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()?.let { playlistTracks(it) }
        else -> null
    }

    /** Recherche de titres (première page, filtre « Titres »). */
    suspend fun search(query: String): List<Track> {
        val page = streams.search(query, SearchFilter.SONGS)
        val tracks = page.items.filterIsInstance<SearchResult.TrackResult>().map { it.track }
        if (tracks.isNotEmpty()) trackCache.put(tracks)
        return tracks
    }

    /** Métadonnées d'un titre : cache local d'abord, réseau en secours. */
    suspend fun loadTrack(videoId: String): Track? {
        trackCache.get(videoId)?.let { return it }
        return runCatching { streams.track(videoId) }.getOrNull()?.also { trackCache.put(listOf(it)) }
    }

    private suspend fun playlistTracks(playlistId: Long): List<Track> =
        playlists.observePlaylist(playlistId).first()?.entries?.map { it.track }.orEmpty()

    private fun playlistItem(playlist: Playlist): MediaItem = container(
        id = PLAYLIST_PREFIX + playlist.id,
        title = playlist.name,
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        subtitle = context.resources.getQuantityString(R.plurals.player_track_count, playlist.trackCount, playlist.trackCount),
        artworkUrl = playlist.thumbnailUrl,
    )

    private fun container(
        id: String,
        title: String,
        mediaType: Int,
        subtitle: String? = null,
        artworkUrl: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build(),
        )
        .build()

    companion object {
        const val ROOT_ID = "root"
        const val LIKED_ID = "liked"
        const val PLAYLISTS_ID = "playlists"
        const val RECENT_ID = "recent"
        const val PLAYLIST_PREFIX = "playlist/"
        private const val RECENT_LIMIT = 50
    }
}
