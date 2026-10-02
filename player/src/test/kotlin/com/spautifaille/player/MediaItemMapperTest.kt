package com.spautifaille.player

import androidx.media3.common.MediaMetadata
import com.spautifaille.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaItemMapperTest {

    private val track = Track(
        id = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        artistUrl = "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
        album = "Whenever You Need Somebody",
        durationMs = 213_000,
        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    )

    @Test
    fun `round trip keeps every field`() {
        val restored = MediaItemMapper.toTrack(MediaItemMapper.toMediaItem(track))
        assertEquals(track, restored)
    }

    @Test
    fun `source id round trips on the media item and is absent by default`() {
        val withSource = MediaItemMapper.toMediaItems(listOf(track), sourceId = "playlist:7").single()
        assertEquals("playlist:7", MediaItemMapper.sourceId(withSource))
        assertEquals(track, MediaItemMapper.toTrack(withSource))
        assertNull(MediaItemMapper.sourceId(MediaItemMapper.toMediaItem(track)))
        // ensureUid ne perd pas l'origine.
        assertEquals("playlist:7", MediaItemMapper.sourceId(MediaItemMapper.ensureUid(withSource)))
    }

    @Test
    fun `round trip with only mandatory fields`() {
        val minimal = Track(id = "abc", title = "T", artist = "A")
        assertEquals(minimal, MediaItemMapper.toTrack(MediaItemMapper.toMediaItem(minimal)))
    }

    @Test
    fun `media item exposes stable uri and playable metadata`() {
        val item = MediaItemMapper.toMediaItem(track)
        assertEquals(track.id, item.mediaId)
        assertEquals("spautifaille://track/dQw4w9WgXcQ", item.localConfiguration?.uri.toString())
        val metadata = item.mediaMetadata
        assertEquals(true, metadata.isPlayable)
        assertEquals(false, metadata.isBrowsable)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, metadata.mediaType)
        assertEquals("Never Gonna Give You Up", metadata.title.toString())
        assertEquals("Rick Astley", metadata.artist.toString())
        assertEquals("Whenever You Need Somebody", metadata.albumTitle.toString())
        assertEquals(213_000L, metadata.durationMs)
        assertEquals(track.thumbnailUrl, metadata.artworkUri.toString())
        assertEquals(track.artistUrl, metadata.extras?.getString(MediaItemMapper.EXTRA_ARTIST_URL))
    }

    @Test
    fun `each occurrence gets its own queue uid`() {
        val first = MediaItemMapper.toMediaItem(track)
        val second = MediaItemMapper.toMediaItem(track)
        assertNotNull(MediaItemMapper.queueUid(first))
        assertNotEquals(MediaItemMapper.queueUid(first), MediaItemMapper.queueUid(second))
        assertEquals("fixed", MediaItemMapper.queueUid(MediaItemMapper.toMediaItem(track, uid = "fixed")))
    }

    @Test
    fun `ensureUid keeps an existing uid and adds a missing one`() {
        val withUid = MediaItemMapper.toMediaItem(track, uid = "u1")
        assertSame(withUid, MediaItemMapper.ensureUid(withUid))

        val bare = androidx.media3.common.MediaItem.Builder().setMediaId("xyz").build()
        assertNull(MediaItemMapper.queueUid(bare))
        val completed = MediaItemMapper.ensureUid(bare)
        assertTrue(MediaItemMapper.queueUid(completed)?.isNotEmpty() == true)
        assertEquals("xyz", completed.mediaId)
    }

    @Test
    fun `missing duration and artwork stay null`() {
        val item = androidx.media3.common.MediaItem.Builder()
            .setMediaId("id1")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("t").build())
            .build()
        val restored = MediaItemMapper.toTrack(item)
        assertNull(restored.durationMs)
        assertNull(restored.thumbnailUrl)
        assertNull(restored.artistUrl)
        assertFalse(restored.artist.isNotEmpty())
    }
}
