package com.spautifaille.data.newpipe

import android.util.Log
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Artist
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
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.domain.repository.TrackCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/** Implémentation de [StreamRepository] basée sur NewPipeExtractor (service YouTube). */
@Singleton
class NewPipeStreamRepository @Inject constructor(
    private val initializer: NewPipeInitializer,
    private val trackCache: TrackCache,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StreamRepository {

    private val service get() = ServiceList.YouTube

    override suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return call { service.suggestionExtractor.suggestionList(query).orEmpty() }
    }

    override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Paged<SearchResult> =
        call {
            val token = page as? NewPipePageToken
            if (token != null) {
                val contentFilter = token.contentFilter ?: filter.contentFilter()
                val (items, nextPage) = searchPage(query, contentFilter, token.page)
                return@call toPaged(items, nextPage, contentFilter)
            }
            // Repli : YouTube Music renvoie parfois « aucun résultat » pour Titres / Albums (selon l'IP ou la
            // région) alors que Vidéos / Playlists fonctionnent.
            var lastResult: Paged<SearchResult>? = null
            for (contentFilter in searchFallbacks(filter)) {
                val (items, nextPage) = searchPage(query, contentFilter, null)
                val paged = toPaged(items, nextPage, contentFilter)
                if (paged.items.isNotEmpty()) return@call paged
                lastResult = paged
            }
            lastResult ?: Paged(emptyList(), null)
        }

    private fun searchPage(query: String, contentFilter: String, page: Page?): Pair<List<InfoItem>, Page?> {
        val handler = service.searchQHFactory.fromQuery(query, listOf(contentFilter), "")
        // Filtres YouTube Music : extracteur patché (paramètres de filtre à jour), cf. PatchedYoutubeMusicSearchExtractor.
        val musicExtractor = if (contentFilter.startsWith("music_")) {
            PatchedYoutubeMusicSearchExtractor(service, handler)
        } else {
            null
        }
        return if (page == null) {
            val info = if (musicExtractor != null) {
                musicExtractor.fetchPage()
                SearchInfo.getInfo(musicExtractor)
            } else {
                SearchInfo.getInfo(service, handler)
            }
            info.relatedItems.orEmpty() to info.nextPage
        } else {
            val more = musicExtractor?.getPage(page) ?: SearchInfo.getMoreItems(service, handler, page)
            more.items.orEmpty() to more.nextPage
        }
    }

    private suspend fun toPaged(items: List<InfoItem>, nextPage: Page?, contentFilter: String): Paged<SearchResult> {
        val results = items.mapNotNull(NewPipeMappers::toSearchResult)
        cachePut(results.mapNotNull { (it as? SearchResult.TrackResult)?.track })
        val next = if (nextPage != null && Page.isValid(nextPage)) NewPipePageToken(nextPage, contentFilter = contentFilter) else null
        return Paged(results, next)
    }

    private fun searchFallbacks(filter: SearchFilter): List<String> = when (filter) {
        SearchFilter.SONGS -> listOf(
            YoutubeSearchQueryHandlerFactory.MUSIC_SONGS,
            YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS,
            YoutubeSearchQueryHandlerFactory.VIDEOS,
        )
        SearchFilter.ALBUMS -> listOf(
            YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS,
            YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS,
        )
        SearchFilter.ARTISTS -> listOf(
            YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS,
            YoutubeSearchQueryHandlerFactory.CHANNELS,
        )
        else -> listOf(filter.contentFilter())
    }

    override suspend fun track(videoId: String): Track {
        val cached = try {
            trackCache.get(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Track cache read failed for $videoId", e)
            null
        }
        if (cached != null) return cached
        return call {
            val info = StreamInfo.getInfo(service, watchUrl(videoId))
            NewPipeMappers.toTrack(info, videoId).also { cachePut(listOf(it)) }
        }
    }

    override suspend fun resolveAudio(videoId: String, quality: AudioQuality): ResolvedStream = call {
        val info = StreamInfo.getInfo(service, watchUrl(videoId))
        cachePut(listOf(NewPipeMappers.toTrack(info, videoId)))

        val streams = info.audioStreams.orEmpty()
        val candidates = streams.mapIndexed { index, stream -> stream.toCandidate(index) }
        val chosen = AudioStreamSelector.select(candidates, quality)
            ?: throw AppException(AppError.NoAudioStream)
        buildResolvedStream(videoId, streams[chosen.index])
    }

    override suspend fun related(videoId: String): List<Track> = call {
        val info = StreamInfo.getInfo(service, watchUrl(videoId))
        NewPipeMappers.toTracks(info.relatedItems).filter { it.id != videoId }.also { cachePut(it) }
    }

    override suspend fun mix(videoId: String): List<Track> =
        try {
            call {
                val info = PlaylistInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId")
                NewPipeMappers.toTracks(info.relatedItems).also { cachePut(it) }
            }
        } catch (e: AppException) {
            Log.w(TAG, "Mix unavailable for $videoId: ${e.error}", e)
            emptyList()
        }

    override suspend fun remotePlaylist(url: String, page: PageToken?): RemotePlaylistPage = call {
        val token = page as? NewPipePageToken
        if (token == null) {
            val info = PlaylistInfo.getInfo(service, url)
            val playlist = RemotePlaylist(
                url = url,
                name = info.name.orEmpty(),
                uploader = NewPipeMappers.cleanArtistName(info.uploaderName).takeIf { it.isNotEmpty() },
                thumbnailUrl = NewPipeMappers.pickThumbnail(info.thumbnails),
                trackCount = info.streamCount.takeIf { it >= 0 },
            )
            val tracks = NewPipeMappers.toTracks(info.relatedItems).also { cachePut(it) }
            RemotePlaylistPage(playlist, Paged(tracks, nextToken(info.nextPage, playlist)))
        } else {
            val more = PlaylistInfo.getMoreItems(service, url, token.page)
            val playlist = token.playlist ?: RemotePlaylist(url = url, name = "")
            val tracks = NewPipeMappers.toTracks(more.items).also { cachePut(it) }
            RemotePlaylistPage(playlist, Paged(tracks, nextToken(more.nextPage, playlist)))
        }
    }

    override suspend fun artist(url: String): ArtistDetails = call {
        val info = ChannelInfo.getInfo(service, url)
        val tabs = info.tabs.orEmpty()

        fun tab(vararg ids: String): ListLinkHandler? =
            ids.firstNotNullOfOrNull { id -> tabs.firstOrNull { id in it.contentFilters.orEmpty() } }

        val tracks = tab(ChannelTabs.TRACKS, ChannelTabs.VIDEOS)?.let { handler ->
            NewPipeMappers.toTracks(tabItems(handler))
        }.orEmpty()
        val playlists = listOfNotNull(tab(ChannelTabs.PLAYLISTS), tab(ChannelTabs.ALBUMS))
            .flatMap { handler -> tabItems(handler).filterIsInstance<PlaylistInfoItem>() }
            .map(NewPipeMappers::toPlaylist)
            .distinctBy { it.url }

        cachePut(tracks)
        ArtistDetails(
            artist = Artist(
                url = info.url,
                name = NewPipeMappers.cleanArtistName(info.name),
                avatarUrl = NewPipeMappers.pickThumbnail(info.avatars),
                subscriberCount = info.subscriberCount.takeIf { it >= 0 },
            ),
            description = info.description?.takeIf { it.isNotBlank() },
            bannerUrl = NewPipeMappers.pickLargest(info.banners),
            tracks = tracks,
            playlists = playlists,
        )
    }

    override fun isPlaylistUrl(url: String): Boolean = try {
        YoutubePlaylistLinkHandlerFactory.getInstance().acceptUrl(url)
    } catch (e: Exception) {
        false
    }

    // --- Internals ---------------------------------------------------------------------------------

    /**
     * Exécute [block] sur le dispatcher IO, initialise NewPipe au besoin, rejoue sur erreurs
     * réseau / bot, puis convertit toute exception en [AppException].
     */
    private suspend fun <T> call(block: suspend () -> T): T = withContext(io) {
        try {
            retryWithBackoff(shouldRetry = { it.isRetryable() }) {
                initializer.ensureInitialized()
                block()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NewPipeErrorMapper.map(e)
        }
    }

    private fun Throwable.isRetryable(): Boolean {
        val error = NewPipeErrorMapper.mapToError(this)
        return error is AppError.Network || error is AppError.BotDetected
    }

    /** Un onglet de chaîne en échec ne doit pas faire échouer la page artiste. */
    private fun tabItems(handler: ListLinkHandler): List<org.schabi.newpipe.extractor.InfoItem> = try {
        ChannelTabInfo.getInfo(service, handler).relatedItems.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Channel tab ${handler.contentFilters} failed: ${e.message}", e)
        emptyList()
    }

    private suspend fun cachePut(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        try {
            trackCache.put(tracks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Track cache write failed", e)
        }
    }

    private fun nextToken(page: Page?, playlist: RemotePlaylist? = null): PageToken? =
        if (page != null && Page.isValid(page)) NewPipePageToken(page, playlist) else null

    private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private fun SearchFilter.contentFilter(): String = when (this) {
        SearchFilter.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
        SearchFilter.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
        SearchFilter.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
    }

    private fun AudioStream.toCandidate(index: Int) = AudioCandidate(
        index = index,
        bitrateKbps = averageBitrate,
        container = when (format) {
            MediaFormat.WEBMA_OPUS, MediaFormat.OPUS -> AudioContainer.OPUS
            MediaFormat.M4A -> AudioContainer.M4A
            else -> AudioContainer.OTHER
        },
        isUrl = isUrl,
        delivery = when (deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> AudioDelivery.PROGRESSIVE_HTTP
            DeliveryMethod.DASH -> AudioDelivery.DASH
            else -> AudioDelivery.OTHER
        },
        trackKind = when (audioTrackType) {
            null -> null
            AudioTrackType.ORIGINAL -> AudioTrackKind.ORIGINAL
            else -> AudioTrackKind.OTHER
        },
        itag = itag,
    )

    private fun buildResolvedStream(videoId: String, stream: AudioStream): ResolvedStream {
        val content = stream.content
        val isDash = !stream.isUrl
        val headerProbe = content
        val bitrateBps = when {
            stream.bitrate > 0 -> stream.bitrate
            stream.averageBitrate > 0 -> stream.averageBitrate * 1000
            else -> 0
        }
        return ResolvedStream(
            videoId = videoId,
            url = if (isDash) stream.manifestUrl.orEmpty() else content,
            mimeType = stream.format?.mimeType,
            codec = stream.codec?.takeIf { it.isNotBlank() },
            bitrate = bitrateBps,
            contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 },
            headers = StreamUrls.headersFor(headerProbe) { YoutubeParsingHelper.getVisionOsUserAgent(null) },
            expiresAtMs = StreamUrls.expiresAtMs(content, System.currentTimeMillis()),
            dashManifest = if (isDash) content else null,
        )
    }

    private companion object {
        const val TAG = "NewPipeRepo"
    }
}
