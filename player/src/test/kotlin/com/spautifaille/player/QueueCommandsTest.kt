package com.spautifaille.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.domain.model.Track
import com.spautifaille.player.queue.QueueSnapshots
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueCommandsTest {

    private lateinit var player: ExoPlayer

    private fun items(prefix: String, n: Int) =
        MediaItemMapper.toMediaItems(List(n) { Track("$prefix$it", "Titre $prefix$it", "Artiste") })

    private fun ids() = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    /** Identifiants dans l'ordre de lecture aléatoire. */
    private fun shuffledIds() =
        QueueSnapshots.shuffleOrder(player.currentTimeline).map { player.getMediaItemAt(it).mediaId }

    @Before
    fun setUp() {
        player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
    }

    @After
    fun tearDown() {
        player.release()
    }

    // --- shuffleOrderWithInsertedAfter (pur) ---

    @Test
    fun `inserted windows come right after the current one and old indices shift`() {
        // Ordre de lecture des fenêtres 0..4 : 3, 1, 4, 0, 2 ; courant = 1 ; on insère 2 titres (fenêtres 2 et 3).
        val result = QueueCommands.shuffleOrderWithInsertedAfter(listOf(3, 1, 4, 0, 2), currentIndex = 1, count = 2)
        // Anciens indices >= 2 décalés de 2 : 3->5, 4->6, 2->4.
        assertArrayEquals(intArrayOf(5, 1, 2, 3, 6, 0, 4), result)
    }

    @Test
    fun `insertion after the last window of the order`() {
        val result = QueueCommands.shuffleOrderWithInsertedAfter(listOf(2, 0, 1), currentIndex = 1, count = 1)
        assertArrayEquals(intArrayOf(3, 0, 1, 2), result)
    }

    @Test
    fun `result is always a permutation with the new windows right after the current one`() {
        val order = listOf(4, 2, 0, 3, 1)
        for (current in 0..4) {
            for (count in 1..3) {
                val result = QueueCommands.shuffleOrderWithInsertedAfter(order, current, count)
                assertEquals(5 + count, result.size)
                assertEquals((0 until 5 + count).toSet(), result.toSet())
                val at = result.indexOf(current)
                for (i in 0 until count) assertEquals(current + 1 + i, result[at + 1 + i])
            }
        }
    }

    // --- playNext sur un vrai ExoPlayer ---

    @Test
    fun `play next under shuffle plays the new tracks right after the current one`() {
        repeat(20) {
            player.shuffleModeEnabled = true
            player.setMediaItems(items("q", 8), 3, 0)
            QueueCommands.playNext(player, items("n", 2))

            assertEquals(10, player.mediaItemCount)
            assertEquals(3, player.currentMediaItemIndex)
            assertEquals("q3", player.currentMediaItem?.mediaId)
            val order = shuffledIds()
            assertEquals(10, order.size)
            val at = order.indexOf("q3")
            assertEquals(listOf("n0", "n1"), order.subList(at + 1, at + 3))
            assertEquals(ids().toSet(), order.toSet())
            player.clearMediaItems()
        }
    }

    @Test
    fun `play next without shuffle inserts after the current index`() {
        player.setMediaItems(items("q", 4), 1, 0)
        QueueCommands.playNext(player, items("n", 2))
        assertEquals(listOf("q0", "q1", "n0", "n1", "q2", "q3"), ids())
    }

    @Test
    fun `play next on an empty queue just adds the tracks`() {
        QueueCommands.playNext(player, items("n", 2))
        assertEquals(listOf("n0", "n1"), ids())
    }

    // --- setQueue ---

    @Test
    fun `non shuffled play keeps the user's shuffle mode`() {
        player.shuffleModeEnabled = true
        QueueCommands.setQueue(player, items("q", 5), startIndex = 2, shuffle = false)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(2, player.currentMediaItemIndex)

        player.shuffleModeEnabled = false
        QueueCommands.setQueue(player, items("q", 5), startIndex = 4, shuffle = false)
        assertFalse(player.shuffleModeEnabled)
        assertEquals(4, player.currentMediaItemIndex)
    }

    @Test
    fun `shuffled play without start begins at the first window of the shuffle order`() {
        val starts = mutableSetOf<Int>()
        repeat(30) {
            QueueCommands.setQueue(player, items("q", 20), startIndex = 0, shuffle = true)
            assertTrue(player.shuffleModeEnabled)
            assertEquals(player.currentTimeline.getFirstWindowIndex(true), player.currentMediaItemIndex)
            // Tous les titres sont atteignables dans l'ordre mélangé, à partir du titre courant.
            assertEquals(20, QueueSnapshots.shuffleOrder(player.currentTimeline).toSet().size)
            starts += player.currentMediaItemIndex
            player.clearMediaItems()
        }
        assertTrue("start index should be random, was always ${starts.first()}", starts.size > 1)
    }

    @Test
    fun `shuffled play with an explicit start honours it`() {
        QueueCommands.setQueue(player, items("q", 6), startIndex = 4, shuffle = true)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(4, player.currentMediaItemIndex)
    }

    @Test
    fun `start index is clamped`() {
        QueueCommands.setQueue(player, items("q", 3), startIndex = 99, shuffle = false)
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }
}
