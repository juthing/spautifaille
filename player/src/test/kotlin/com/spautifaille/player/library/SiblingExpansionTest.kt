package com.spautifaille.player.library

import androidx.media3.common.MediaItem
import com.spautifaille.domain.model.Track
import com.spautifaille.player.MediaItemMapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SiblingExpansionTest {

    private val liked = List(4) { Track("v$it", "Titre $it", "Artiste") }
    private val lookup: suspend (String) -> List<Track>? = { if (it == "liked") liked else null }

    private fun tapped(id: String, parent: String?): MediaItem =
        MediaItemMapper.toMediaItem(Track(id, id, "A"), parentId = parent)

    @Test
    fun `a tapped track expands to all siblings starting at its index`() = runTest {
        val result = SiblingExpansion.expand(listOf(tapped("v2", "liked")), 1_500L, lookup)!!
        assertEquals(liked.map { it.id }, result.mediaItems.map { it.mediaId })
        assertEquals(2, result.startIndex)
        assertEquals(1_500L, result.startPositionMs)
    }

    @Test
    fun `parent id round trips through the mapper`() {
        val item = MediaItemMapper.toMediaItem(liked.first(), parentId = "playlist/7")
        assertEquals("playlist/7", MediaItemMapper.parentId(item))
        assertNull(MediaItemMapper.parentId(MediaItemMapper.toMediaItem(liked.first())))
    }

    @Test
    fun `no parent, several items, unknown parent or missing track leave the request untouched`() = runTest {
        assertNull(SiblingExpansion.expand(listOf(tapped("v1", null)), 0, lookup))
        assertNull(SiblingExpansion.expand(listOf(tapped("v1", "liked"), tapped("v2", "liked")), 0, lookup))
        assertNull(SiblingExpansion.expand(listOf(tapped("v1", "playlist/9")), 0, lookup))
        assertNull(SiblingExpansion.expand(listOf(tapped("gone", "liked")), 0, lookup))
        assertNull(SiblingExpansion.expand(emptyList(), 0, lookup))
    }

    @Test
    fun `expanded items get their own queue uids`() = runTest {
        val result = SiblingExpansion.expand(listOf(tapped("v0", "liked")), 0, lookup)!!
        val uids = result.mediaItems.map { MediaItemMapper.queueUid(it) }
        assertEquals(uids.size, uids.toSet().size)
    }
}
