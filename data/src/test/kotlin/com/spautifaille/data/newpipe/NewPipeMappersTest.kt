package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class NewPipeMappersTest {

    private fun image(url: String, width: Int, height: Int = width * 9 / 16) =
        Image(url, height, width, Image.ResolutionLevel.fromHeight(height))

    private fun streamItem(
        url: String = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        type: StreamType = StreamType.VIDEO_STREAM,
    ) = StreamInfoItem(0, url, "Never Gonna Give You Up", type).apply {
        uploaderName = "Rick Astley - Topic"
        uploaderUrl = "https://www.youtube.com/channel/UC123"
        duration = 213
        thumbnails = listOf(image("small", 168), image("medium", 336), image("huge", 1280))
    }

    @Test fun streamItemBecomesTrack() {
        val track = NewPipeMappers.toTrack(streamItem())!!
        assertEquals("dQw4w9WgXcQ", track.id)
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals("https://www.youtube.com/channel/UC123", track.artistUrl)
        assertEquals(213_000L, track.durationMs)
        assertEquals("medium", track.thumbnailUrl)
        assertNull(track.album)
    }

    @Test fun zeroDurationIsNull() {
        val track = NewPipeMappers.toTrack(streamItem().apply { duration = 0 })!!
        assertNull(track.durationMs)
    }

    @Test fun musicYoutubeUrlsAreSupported() {
        val track = NewPipeMappers.toTrack(streamItem(url = "https://music.youtube.com/watch?v=dQw4w9WgXcQ"))!!
        assertEquals("dQw4w9WgXcQ", track.id)
    }

    @Test fun liveStreamsAreSkipped() {
        assertNull(NewPipeMappers.toTrack(streamItem(type = StreamType.LIVE_STREAM)))
        assertNull(NewPipeMappers.toTrack(streamItem(type = StreamType.AUDIO_LIVE_STREAM)))
    }

    @Test fun invalidUrlIsSkipped() {
        assertNull(NewPipeMappers.toTrack(streamItem(url = "https://example.com/nothing")))
    }

    @Test fun onlyStripsTopicSuffix() {
        assertEquals("Daft Punk", NewPipeMappers.cleanArtistName("Daft Punk - Topic"))
        assertEquals("Topic", NewPipeMappers.cleanArtistName("Topic"))
        assertEquals("A - Topic Band", NewPipeMappers.cleanArtistName("A - Topic Band"))
        assertEquals("", NewPipeMappers.cleanArtistName(null))
    }

    @Test fun thumbnailPicksLargestUpTo720() {
        val images = listOf(image("a", 120), image("b", 480), image("c", 720), image("d", 1920))
        assertEquals("c", NewPipeMappers.pickThumbnail(images))
    }

    @Test fun thumbnailFallsBackToSmallestWhenAllTooLarge() {
        assertEquals("big1", NewPipeMappers.pickThumbnail(listOf(image("big2", 1920), image("big1", 1280))))
    }

    @Test fun thumbnailHandlesUnknownSizesAndEmpty() {
        val unknown = Image("u", Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN)
        assertEquals("u", NewPipeMappers.pickThumbnail(listOf(unknown)))
        assertNull(NewPipeMappers.pickThumbnail(emptyList()))
        assertNull(NewPipeMappers.pickThumbnail(null))
    }

    @Test fun playlistItemBecomesRemotePlaylist() {
        val item = PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PL1", "Mix").apply {
            uploaderName = "Someone"
            streamCount = 42
            thumbnails = listOf(image("t", 480))
        }
        val playlist = NewPipeMappers.toPlaylist(item)
        assertEquals("https://www.youtube.com/playlist?list=PL1", playlist.url)
        assertEquals("Mix", playlist.name)
        assertEquals("Someone", playlist.uploader)
        assertEquals(42L, playlist.trackCount)
        assertEquals("t", playlist.thumbnailUrl)
    }

    @Test fun channelItemBecomesArtist() {
        val item = ChannelInfoItem(0, "https://www.youtube.com/channel/UC1", "Artist - Topic").apply {
            subscriberCount = 1234
            thumbnails = listOf(image("avatar", 176))
        }
        val artist = NewPipeMappers.toArtist(item)
        assertEquals("Artist", artist.name)
        assertEquals(1234L, artist.subscriberCount)
        assertEquals("avatar", artist.avatarUrl)
    }

    @Test fun searchResultMappingCoversAllSupportedTypes() {
        assertTrue(NewPipeMappers.toSearchResult(streamItem()) is SearchResult.TrackResult)
        assertTrue(
            NewPipeMappers.toSearchResult(PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PL1", "p"))
                is SearchResult.PlaylistResult,
        )
        assertTrue(
            NewPipeMappers.toSearchResult(ChannelInfoItem(0, "https://www.youtube.com/channel/UC1", "c"))
                is SearchResult.ArtistResult,
        )
        assertNull(NewPipeMappers.toSearchResult(streamItem(type = StreamType.LIVE_STREAM)))
    }
}
