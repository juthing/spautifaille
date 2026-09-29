package com.spautifaille.player.queue

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.QueueStateStore
import com.spautifaille.player.MediaItemMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueuePersisterTest {

    private lateinit var player: ExoPlayer
    private val store = mockk<QueueStateStore>(relaxed = true)
    private val scope = TestScope(UnconfinedTestDispatcher())
    private lateinit var persister: QueuePersister

    private fun items(n: Int) = MediaItemMapper.toMediaItems(List(n) { Track("id$it", "Titre $it", "A") })

    @Before
    fun setUp() {
        player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
        persister = QueuePersister(player, store, scope, scope)
        persister.arm()
    }

    @After
    fun tearDown() {
        persister.release()
        player.release()
    }

    @Test
    fun `unchanged queue only rewrites index and position`() {
        player.setMediaItems(items(3), 1, 500)
        persister.flushNow()
        coVerify(exactly = 1) { store.save(any()) }

        player.seekTo(2, 0)
        persister.flushNow()
        persister.flushNow()
        coVerify(exactly = 1) { store.save(any()) }
        coVerify(exactly = 2) { store.savePosition(2, any()) }
    }

    @Test
    fun `queue, repeat and shuffle changes trigger a full save`() {
        player.setMediaItems(items(3), 0, 0)
        persister.flushNow()
        coVerify(exactly = 1) { store.save(any()) }

        player.addMediaItem(items(1).first())
        persister.flushNow()
        coVerify(exactly = 2) { store.save(any()) }

        player.repeatMode = Player.REPEAT_MODE_ALL
        persister.flushNow()
        coVerify(exactly = 3) { store.save(any()) }

        player.shuffleModeEnabled = true
        persister.flushNow()
        coVerify(exactly = 4) { store.save(any()) }
    }

    @Test
    fun `a failed save is retried in full next time`() {
        coEvery { store.save(any()) } throws java.io.IOException("disk")
        player.setMediaItems(items(2), 0, 0)
        persister.flushNow()
        persister.flushNow()
        coVerify(exactly = 2) { store.save(any()) }
    }

    @Test
    fun `empty queue clears the store once armed`() {
        persister.flushNow()
        coVerify(exactly = 1) { store.clear() }
    }
}
