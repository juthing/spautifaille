package com.spautifaille.player.queue

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.RepeatMode
import com.spautifaille.domain.repository.QueueSnapshot
import com.spautifaille.player.MediaItemMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueSnapshotsTest {

    private lateinit var player: ExoPlayer
    private val tracks = (0 until 5).map { Track("id$it", "Titre $it", "Artiste") }

    @Before
    fun setUp() {
        player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun `empty player has no snapshot`() {
        assertNull(QueueSnapshots.capture(player))
    }

    @Test
    fun `restore then capture returns the same queue without preparing`() {
        val snapshot = QueueSnapshot(
            tracks = tracks,
            currentIndex = 3,
            positionMs = 12_345,
            shuffleEnabled = false,
            repeatMode = RepeatMode.ALL,
        )
        QueueSnapshots.restore(player, snapshot)

        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        val captured = QueueSnapshots.capture(player)!!
        assertEquals(tracks, captured.tracks)
        assertEquals(3, captured.currentIndex)
        assertEquals(12_345L, captured.positionMs)
        assertEquals(RepeatMode.ALL, captured.repeatMode)
        assertTrue(captured.shuffleOrder.isEmpty())
    }

    @Test
    fun `shuffle order survives a restore`() {
        val order = listOf(2, 4, 0, 3, 1)
        QueueSnapshots.restore(
            player,
            QueueSnapshot(tracks, currentIndex = 2, positionMs = 0, shuffleEnabled = true, repeatMode = RepeatMode.OFF, shuffleOrder = order),
        )
        val captured = QueueSnapshots.capture(player)!!
        assertTrue(captured.shuffleEnabled)
        assertEquals(order, captured.shuffleOrder)
    }

    @Test
    fun `invalid shuffle order is ignored`() {
        assertFalse(QueueSnapshots.isPermutation(listOf(0, 0, 1), 3))
        assertFalse(QueueSnapshots.isPermutation(listOf(0, 1), 3))
        assertTrue(QueueSnapshots.isPermutation(listOf(2, 0, 1), 3))
        QueueSnapshots.restore(
            player,
            QueueSnapshot(tracks, 0, 0, shuffleEnabled = true, repeatMode = RepeatMode.OFF, shuffleOrder = listOf(0, 0, 0, 0, 0)),
        )
        val order = QueueSnapshots.capture(player)!!.shuffleOrder
        assertEquals(setOf(0, 1, 2, 3, 4), order.toSet())
    }

    @Test
    fun `restored items carry unique uids`() {
        QueueSnapshots.restore(player, QueueSnapshot(tracks + tracks.first(), 0, 0, false, RepeatMode.OFF))
        val uids = (0 until player.mediaItemCount).map { MediaItemMapper.queueUid(player.getMediaItemAt(it)) }
        assertEquals(uids.size, uids.toSet().size)
    }
}
