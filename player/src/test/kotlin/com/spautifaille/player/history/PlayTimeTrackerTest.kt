package com.spautifaille.player.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTimeTrackerTest {

    @Test
    fun `records after 30 seconds for long tracks`() {
        val tracker = PlayTimeTracker()
        tracker.addPlayed(29_999)
        assertFalse(tracker.shouldRecord(300_000))
        tracker.addPlayed(1)
        assertTrue(tracker.shouldRecord(300_000))
    }

    @Test
    fun `records at half the duration for short tracks`() {
        val tracker = PlayTimeTracker()
        tracker.addPlayed(9_999)
        assertFalse(tracker.shouldRecord(20_000))
        tracker.addPlayed(1)
        assertTrue(tracker.shouldRecord(20_000))
    }

    @Test
    fun `records only once per occurrence and again after reset`() {
        val tracker = PlayTimeTracker()
        tracker.addPlayed(40_000)
        assertTrue(tracker.shouldRecord(200_000))
        assertFalse(tracker.shouldRecord(200_000))
        tracker.reset()
        assertFalse(tracker.shouldRecord(200_000))
        tracker.addPlayed(31_000)
        assertTrue(tracker.shouldRecord(200_000))
    }

    @Test
    fun `unknown duration falls back to the 30 second rule`() {
        val tracker = PlayTimeTracker()
        tracker.addPlayed(20_000)
        assertFalse(tracker.shouldRecord(0))
        tracker.addPlayed(10_000)
        assertTrue(tracker.shouldRecord(0))
    }
}
