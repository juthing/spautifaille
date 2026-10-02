package com.spautifaille.player.history

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.domain.model.Track
import com.spautifaille.player.MediaItemMapper
import com.spautifaille.player.queue.QueueSnapshots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * « Précédent » fondé sur l'historique réel, sur un vrai ExoPlayer (Robolectric) : file remplacée, redémarrage
 * après 3 s, mode aléatoire, pile bornée, transitions dues à « précédent » non réempilées.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryNavigationTest {

    private lateinit var exo: ExoPlayer
    private lateinit var history: SessionHistory
    private lateinit var player: Player

    private fun items(prefix: String, n: Int): List<MediaItem> =
        MediaItemMapper.toMediaItems(List(n) { Track("$prefix$it", "Titre $prefix$it", "Artiste") })

    private fun ids() = List(exo.mediaItemCount) { exo.getMediaItemAt(it).mediaId }
    private fun current() = exo.currentMediaItem?.mediaId
    private fun historyIds() = history.snapshot().map { it.mediaId }

    private fun shuffledIds() = QueueSnapshots.shuffleOrder(exo.currentTimeline).map { exo.getMediaItemAt(it).mediaId }

    @Before
    fun setUp() {
        exo = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
        history = SessionHistory()
        exo.addListener(history)
        history.reset(null)
        player = HistoryAwarePlayer(exo, history)
    }

    @After
    fun tearDown() {
        exo.release()
    }

    // --- Remplacement de la file (le bug) ---

    @Test
    fun `previous after replacing the queue returns to the track actually listened to`() {
        exo.setMediaItems(items("d", 3), 1, 0) // Découverte : on écoute d1
        exo.setMediaItems(items("p", 4), 2, 0) // on lance la playlist au titre p2
        assertEquals("p2", current())
        assertEquals(listOf("d1"), historyIds())

        player.seekToPrevious()

        // Avant : p1 (ordre de la playlist). Voulu : d1, réinséré juste avant p2.
        assertEquals("d1", current())
        assertEquals(listOf("p0", "p1", "d1", "p2", "p3"), ids())
        assertEquals(emptyList<String>(), historyIds())
        assertEquals(0L, exo.currentPosition)
    }

    @Test
    fun `next after going back returns to the track we left`() {
        exo.setMediaItems(items("d", 2), 0, 0)
        exo.setMediaItems(items("p", 3), 0, 0)
        player.seekToPrevious()
        assertEquals("d0", current())

        player.seekToNextMediaItem()

        assertEquals("p0", current())
        assertEquals(listOf("d0"), historyIds()) // d0 est de nouveau « écouté avant »
    }

    @Test
    fun `the transition caused by previous does not push the track we leave`() {
        exo.setMediaItems(items("d", 2), 0, 0)
        exo.setMediaItems(items("p", 3), 1, 0)
        player.seekToPrevious()
        assertEquals("d0", current())
        assertFalse("p1 ne doit pas être empilé", historyIds().contains("p1"))
        assertTrue(history.isEmpty)
    }

    @Test
    fun `previous walks back through every replaced queue then falls back to native behaviour`() {
        exo.setMediaItems(items("a", 2), 0, 0)
        exo.seekToNextMediaItem() // a0 -> a1 : pile [a0]
        exo.setMediaItems(items("p", 2), 0, 0) // pile [a0, a1]
        assertEquals(listOf("a0", "a1"), historyIds())

        player.seekToPrevious()
        assertEquals("a1", current())
        player.seekToPrevious()
        assertEquals("a0", current())
        assertEquals(listOf("a0", "a1", "p0", "p1"), ids())
        assertTrue(history.isEmpty)

        // Plus d'historique, rien avant a0 dans la file : « précédent » redémarre simplement le titre.
        player.seekTo(2_000)
        player.seekToPrevious()
        assertEquals("a0", current())
        assertEquals(0L, exo.currentPosition)
        assertEquals(4, exo.mediaItemCount)
    }

    @Test
    fun `clearing the queue then playing something else still goes back to what was listened to`() {
        exo.setMediaItems(items("a", 2), 1, 0)
        exo.clearMediaItems()
        exo.setMediaItems(items("p", 2), 0, 0)
        assertEquals(listOf("a1"), historyIds())
        player.seekToPrevious()
        assertEquals("a1", current())
    }

    // --- Comportement natif préservé ---

    @Test
    fun `when the last listened track is the previous one in the queue the queue is untouched`() {
        exo.setMediaItems(items("q", 3), 0, 0)
        exo.seekToNextMediaItem() // q1, pile [q0]

        player.seekToPrevious()

        assertEquals("q0", current())
        assertEquals(listOf("q0", "q1", "q2"), ids())
        assertTrue(history.isEmpty)
    }

    @Test
    fun `past three seconds previous restarts the current track and keeps the history`() {
        exo.setMediaItems(items("d", 2), 0, 0)
        exo.setMediaItems(items("p", 3), 1, 0)
        exo.seekTo(5_000)

        player.seekToPrevious()

        assertEquals("p1", current())
        assertEquals(0L, exo.currentPosition)
        assertEquals(listOf("d0"), historyIds())
        assertEquals(listOf("p0", "p1", "p2"), ids())
    }

    @Test
    fun `explicit previous media item ignores the restart threshold`() {
        exo.setMediaItems(items("d", 2), 1, 0)
        exo.setMediaItems(items("p", 3), 1, 0)
        exo.seekTo(5_000)

        player.seekToPreviousMediaItem()

        assertEquals("d1", current())
    }

    @Test
    fun `no history means native behaviour`() {
        exo.setMediaItems(items("q", 3), 2, 0)
        player.seekToPrevious()
        assertEquals("q1", current())
        player.seekToPreviousMediaItem()
        assertEquals("q0", current())
        assertTrue(history.isEmpty)
    }

    @Test
    fun `repeated previous walks up the queue without bouncing between two tracks`() {
        exo.setMediaItems(items("q", 4), 3, 0)
        player.seekToPrevious()
        assertEquals("q2", current())
        player.seekToPrevious()
        assertEquals("q1", current())
        player.seekToPrevious()
        assertEquals("q0", current())
        assertTrue("les titres quittés par « précédent » ne sont pas empilés", history.isEmpty)
        player.seekToNextMediaItem()
        assertEquals("q1", current())
        assertEquals(listOf("q0"), historyIds())
    }

    @Test
    fun `empty player does nothing`() {
        assertFalse(HistoryNavigation.goBack(exo, history, restartThresholdAware = true))
        player.seekToPrevious()
        assertEquals(0, exo.mediaItemCount)
    }

    @Test
    fun `a listened track still in the queue is reached without duplicating it`() {
        exo.setMediaItems(items("q", 5), 0, 0)
        exo.seekToDefaultPosition(3) // saut dans la file : pile [q0]

        player.seekToPrevious()

        assertEquals("q0", current())
        assertEquals(5, exo.mediaItemCount)
        assertTrue(history.isEmpty)
    }

    // --- Mode aléatoire ---

    @Test
    fun `shuffle - previous after a queue replacement goes back and next returns to the track left`() {
        repeat(20) {
            exo.shuffleModeEnabled = true
            exo.setMediaItems(items("d", 3), 1, 0)
            exo.setMediaItems(items("p", 6), 2, 0)
            assertEquals("p2", current())

            player.seekToPrevious()

            assertEquals("d1", current())
            assertEquals(7, exo.mediaItemCount)
            // d1 est juste avant p2 dans l'ordre de lecture aléatoire, et l'ordre reste une permutation complète.
            val order = shuffledIds()
            assertEquals(7, order.size)
            assertEquals(ids().toSet(), order.toSet())
            assertEquals("p2", order[order.indexOf("d1") + 1])

            player.seekToNextMediaItem()
            assertEquals("p2", current())
            exo.clearMediaItems()
            history.reset(null)
            exo.shuffleModeEnabled = false
        }
    }

    @Test
    fun `shuffle - when the last listened track is the previous in shuffle order the queue is untouched`() {
        exo.shuffleModeEnabled = true
        exo.setMediaItems(items("q", 8), 3, 0)
        exo.seekToNextMediaItem() // successeur dans l'ordre aléatoire
        val left = history.peek()!!.mediaId
        val countBefore = exo.mediaItemCount

        player.seekToPrevious()

        assertEquals(left, current())
        assertEquals(countBefore, exo.mediaItemCount)
        assertTrue(history.isEmpty)
    }

    // --- Pile (SessionHistory) ---

    @Test
    fun `the stack is bounded and keeps the most recent tracks`() {
        val bounded = SessionHistory(capacity = 3)
        val queue = items("t", 7)
        bounded.reset(queue[0])
        for (i in 1 until queue.size) bounded.onMediaItemTransition(queue[i], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(listOf("t3", "t4", "t5"), bounded.snapshot().map { it.mediaId })
    }

    @Test
    fun `repeat of the same track and relaunching the current track do not push anything`() {
        val queue = items("t", 2)
        val h = SessionHistory()
        h.reset(queue[0])
        h.onMediaItemTransition(queue[0], Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertTrue(h.isEmpty)
        // Même titre (autre occurrence) relancé depuis une liste : on ne revient pas « avant » à lui-même.
        h.onMediaItemTransition(MediaItemMapper.toMediaItem(Track("t0", "x", "y")), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(h.isEmpty)
    }

    @Test
    fun `first transition after startup pushes nothing and changes are notified`() {
        var changes = 0
        val h = SessionHistory(onChanged = { changes++ })
        val queue = items("t", 3)
        h.reset(null)
        h.onMediaItemTransition(queue[0], Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(h.isEmpty)
        assertEquals(0, changes)
        h.onMediaItemTransition(queue[1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, changes)
        h.beginBack(SessionHistory.uidOf(queue[0]))
        h.onMediaItemTransition(queue[0], Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(2, changes)
        assertTrue(h.isEmpty)
    }

    @Test
    fun `a stale back marker is forgotten at the next transition`() {
        val queue = items("t", 4)
        val h = SessionHistory()
        h.reset(queue[0])
        h.beginBack("uid-qui-n-arrive-jamais")
        h.onMediaItemTransition(queue[1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) // pousse t0
        h.onMediaItemTransition(queue[2], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) // pousse t1 (le marqueur est oublié)
        assertEquals(listOf("t0", "t1"), h.snapshot().map { it.mediaId })
    }
}
