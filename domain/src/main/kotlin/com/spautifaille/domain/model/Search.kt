package com.spautifaille.domain.model

enum class SearchFilter { SONGS, VIDEOS, ALBUMS, PLAYLISTS, ARTISTS }

sealed interface SearchResult {
    data class TrackResult(val track: Track) : SearchResult
    data class PlaylistResult(val playlist: RemotePlaylist) : SearchResult
    data class ArtistResult(val artist: Artist) : SearchResult
}

data class RemotePlaylistPage(
    val playlist: RemotePlaylist,
    val tracks: Paged<Track>,
)

data class ArtistDetails(
    val artist: Artist,
    val description: String? = null,
    val bannerUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val playlists: List<RemotePlaylist> = emptyList(),
)
