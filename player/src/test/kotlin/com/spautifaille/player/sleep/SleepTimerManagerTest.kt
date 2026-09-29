package com.spautifaille.player.sleep

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerManagerTest {

    private class FakePlayer : SleepTimerPlayer {
        var pauseCount = 0
        var pauseAtEnd = false
        override var volume = 1f
        override fun pause() {
            pauseCount++
        }

        override fun setPauseAtEndOfMediaItems(enabled: Boolean) {
            pauseAtEnd = enabled
        }
    }

    private class Harness(scope: TestScope) {
        val player = FakePlayer()
        val states = mutableListOf<SleepTimerState>()
        var finished = 0
        val manager = SleepTimerManager(
            scope = scope,
            elapsedRealtimeMs = { BASE + scope.testScheduler.currentTime },
            player = player,
            onStateChanged = { states += it },
            onFinished = { finished++ },
            fadeDurationMs = 10_000,
            fadeStepMs = 1_000,
        )
    }

    @Test
    fun `duration timer pauses at the end and reports its state`() = runTest {
        val h = Harness(this)
        h.manager.start(60_000)
        runCurrent()
        assertEquals(SleepTimerState.At(BASE + 60_000), h.manager.state)

        advanceTimeBy(59_999)
        assertEquals(0, h.player.pauseCount)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, h.player.pauseCount)
        assertEquals(1, h.finished)
        assertEquals(SleepTimerState.Off, h.manager.state)
        assertEquals(listOf(SleepTimerState.At(BASE + 60_000), SleepTimerState.Off), h.states)
    }

    @Test
    fun `volume fades out over the last 10 seconds`() = runTest {
        val h = Harness(this)
        h.manager.start(60_000)
        advanceTimeBy(49_000)
        runCurrent()
        assertEquals(1f, h.player.volume, 0.0001f)

        advanceTimeBy(5_000) // 5 s avant la fin
        runCurrent()
        assertEquals(0.5f, h.player.volume, 0.11f)

        advanceTimeBy(4_500)
        runCurrent()
        assertTrue("volume ${h.player.volume}", h.player.volume <= 0.25f)
    }

    @Test
    fun `volume is restored when playback resumes after the timer`() = runTest {
        val h = Harness(this)
        h.manager.start(20_000)
        advanceTimeBy(21_000)
        runCurrent()
        assertEquals(1, h.player.pauseCount)
        assertTrue(h.player.volume < 1f)

        h.manager.onPlaybackResumed()
        assertEquals(1f, h.player.volume, 0.0001f)
    }

    @Test
    fun `short durations fade over the whole duration`() = runTest {
        val h = Harness(this)
        h.manager.start(4_000)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0.5f, h.player.volume, 0.26f)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(1, h.player.pauseCount)
    }

    @Test
    fun `cancel stops the timer and restores the volume`() = runTest {
        val h = Harness(this)
        h.manager.start(30_000)
        advanceTimeBy(25_000)
        runCurrent()
        assertTrue(h.player.volume < 1f)

        h.manager.cancel()
        assertEquals(SleepTimerState.Off, h.manager.state)
        assertEquals(1f, h.player.volume, 0.0001f)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, h.player.pauseCount)
        assertEquals(0, h.finished)
    }

    @Test
    fun `restarting replaces the previous timer`() = runTest {
        val h = Harness(this)
        h.manager.start(10_000)
        advanceTimeBy(5_000)
        h.manager.start(30_000)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("first timer must not fire", 0, h.player.pauseCount)
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(1, h.player.pauseCount)
        assertEquals(1, h.finished)
    }

    @Test
    fun `end of track mode pauses through the player flag`() = runTest {
        val h = Harness(this)
        h.manager.startEndOfTrack()
        assertTrue(h.player.pauseAtEnd)
        assertEquals(SleepTimerState.EndOfTrack, h.manager.state)

        h.manager.onItemEnded()
        assertFalse(h.player.pauseAtEnd)
        assertEquals(SleepTimerState.Off, h.manager.state)
        assertEquals(1, h.finished)
        // Idempotent : un second signal de fin ne relance rien.
        h.manager.onItemEnded()
        assertEquals(1, h.finished)
    }

    @Test
    fun `cancelling end of track clears the player flag`() = runTest {
        val h = Harness(this)
        h.manager.startEndOfTrack()
        h.manager.cancel()
        assertFalse(h.player.pauseAtEnd)
        assertEquals(SleepTimerState.Off, h.manager.state)
        assertEquals(0, h.finished)
    }

    @Test
    fun `switching from end of track to a duration timer clears the flag`() = runTest {
        val h = Harness(this)
        h.manager.startEndOfTrack()
        h.manager.start(30_000)
        assertFalse(h.player.pauseAtEnd)
        assertEquals(SleepTimerState.At(BASE + 30_000), h.manager.state)
    }

    @Test
    fun `item end is ignored when no end of track timer is active`() = runTest {
        val h = Harness(this)
        h.manager.onItemEnded()
        assertEquals(0, h.finished)
        assertFalse(h.player.pauseAtEnd)
    }

    private companion object {
        const val BASE = 1_000_000L
    }
}
