package com.spautifaille.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.track
import com.spautifaille.domain.player.RepeatMode
import com.spautifaille.domain.repository.QueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class QueueAndCacheTest {

    private lateinit var db: SpautifailleDatabase
    private lateinit var queue: QueueStateStoreImpl
    private lateinit var cache: TrackCacheImpl

    @Before
    fun setUp() {
        db = createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        queue = QueueStateStoreImpl(db, db.queueDao(), db.trackDao())
        cache = TrackCacheImpl(db.trackDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun loadReturnsNullWhenNothingSaved() = runTest {
        assertNull(queue.load())
    }

    @Test
    fun snapshotRoundTrip() = runTest {
        val snapshot = QueueSnapshot(
            tracks = listOf(track("a"), track("b"), track("a"), track("c")),
            currentIndex = 2,
            positionMs = 42_500,
            shuffleEnabled = true,
            repeatMode = RepeatMode.ALL,
            shuffleOrder = listOf(2, 0, 3, 1),
        )

        queue.save(snapshot)

        assertEquals(snapshot, queue.load())
    }

    @Test
    fun snapshotWithoutShuffleOrderRoundTrips() = runTest {
        val snapshot = QueueSnapshot(
            tracks = listOf(track("a")),
            currentIndex = 0,
            positionMs = 0,
            shuffleEnabled = false,
            repeatMode = RepeatMode.ONE,
        )
        queue.save(snapshot)
        assertEquals(snapshot, queue.load())
    }

    @Test
    fun saveReplacesPreviousQueue() = runTest {
        queue.save(QueueSnapshot(listOf(track("a"), track("b"), track("c")), 1, 10, false, RepeatMode.OFF))
        val second = QueueSnapshot(listOf(track("x")), 0, 5, true, RepeatMode.OFF, listOf(0))
        queue.save(second)

        assertEquals(second, queue.load())
    }

    @Test
    fun savePositionUpdatesIndexAndPositionOnly() = runTest {
        val snapshot = QueueSnapshot(listOf(track("a"), track("b")), 0, 0, true, RepeatMode.ALL, listOf(1, 0))
        queue.save(snapshot)

        queue.savePosition(currentIndex = 1, positionMs = 9_000)

        assertEquals(snapshot.copy(currentIndex = 1, positionMs = 9_000), queue.load())
    }

    @Test
    fun clearRemovesQueue() = runTest {
        queue.save(QueueSnapshot(listOf(track("a")), 0, 0, false, RepeatMode.OFF))
        queue.clear()
        assertNull(queue.load())
    }

    @Test
    fun trackCachePutGetAndOverwrite() = runTest {
        assertNull(cache.get("a"))

        cache.put(listOf(track("a"), track("b"), track("a")))
        assertEquals(track("a"), cache.get("a"))
        assertNotNull(cache.get("b"))

        cache.put(listOf(track("a", title = "Titre corrigé")))
        assertEquals("Titre corrigé", cache.get("a")!!.title)
    }
}
