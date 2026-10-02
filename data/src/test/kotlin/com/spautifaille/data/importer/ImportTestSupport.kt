package com.spautifaille.data.importer

import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.PlaylistImporter
import com.spautifaille.domain.model.ArtistDetails
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.Paged
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.RemotePlaylistPage
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.model.TrackStats
import com.spautifaille.domain.repository.StreamRepository

/** [StreamRepository] scriptable ; seules les fonctions utilisées par l'import sont implémentées. */
internal class FakeStreamRepository : StreamRepository {
    val searches = mutableListOf<Pair<String, SearchFilter>>()
    val trackRequests = mutableListOf<String>()
    val playlistRequests = mutableListOf<PageToken?>()

    var searchHandler: (String, SearchFilter) -> List<Track> = { _, _ -> emptyList() }
    var trackHandler: (String) -> Track = { throw AppException(com.spautifaille.domain.error.AppError.Unavailable) }
    var playlistHandler: (PageToken?) -> RemotePlaylistPage = { error("playlist non configurée") }
    var playlistUrls: (String) -> Boolean = { it.contains("list=") }

    override suspend fun suggestions(query: String): List<String> = emptyList()

    override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Paged<SearchResult> {
        searches += query to filter
        return Paged(searchHandler(query, filter).map { SearchResult.TrackResult(it) }, null)
    }

    override suspend fun track(videoId: String): Track {
        trackRequests += videoId
        return trackHandler(videoId)
    }

    override suspend fun resolveAudio(videoId: String, quality: AudioQuality): ResolvedStream = error("inutilisé")
    override suspend fun trackStats(videoId: String): TrackStats = error("inutilisé")
    override suspend fun related(videoId: String): List<Track> = emptyList()
    override suspend fun mix(videoId: String): List<Track> = emptyList()

    override suspend fun remotePlaylist(url: String, page: PageToken?): RemotePlaylistPage {
        playlistRequests += page
        return playlistHandler(page)
    }

    override suspend fun artist(url: String): ArtistDetails = error("inutilisé")
    override fun isPlaylistUrl(url: String): Boolean = playlistUrls(url)
}

internal data class IntToken(val n: Int) : PageToken

internal class FakeScheduler : ImportScheduler {
    val enqueued = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()
    override fun enqueue(jobId: Long) {
        enqueued += jobId
    }

    override fun cancel(jobId: Long) {
        cancelled += jobId
    }
}

internal class FakeImporter(
    private val handles: (ImportSource) -> Boolean = { true },
    private val result: () -> List<ImportedPlaylist>,
) : PlaylistImporter {
    override fun canHandle(source: ImportSource) = handles(source)
    override suspend fun read(source: ImportSource): List<ImportedPlaylist> = result()
}

internal fun remotePage(name: String, tracks: List<Track>, next: PageToken?) =
    RemotePlaylistPage(RemotePlaylist(url = "https://www.youtube.com/playlist?list=PL1", name = name), Paged(tracks, next))

internal fun ytTrack(id: String, title: String = "Titre $id", artist: String = "Artiste $id", durationMs: Long? = 200_000L) =
    Track(id = id, title = title, artist = artist, durationMs = durationMs, thumbnailUrl = "https://img/$id.jpg")
