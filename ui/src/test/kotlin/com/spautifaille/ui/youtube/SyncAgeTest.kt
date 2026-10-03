package com.spautifaille.ui.youtube

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncAgeTest {
    private val now = 1_000_000_000_000L

    private fun ago(value: Long, unit: TimeUnit) = syncAge(now - unit.toMillis(value), now)

    @Test
    fun `under a minute is just now`() {
        assertEquals(SyncAge.JustNow, ago(30, TimeUnit.SECONDS))
        assertEquals(SyncAge.JustNow, ago(0, TimeUnit.SECONDS))
    }

    @Test
    fun `a future date is just now`() {
        assertEquals(SyncAge.JustNow, syncAge(now + 60_000, now))
    }

    @Test
    fun `minutes hours and days`() {
        assertEquals(SyncAge.Minutes(1), ago(60, TimeUnit.SECONDS))
        assertEquals(SyncAge.Minutes(59), ago(59, TimeUnit.MINUTES))
        assertEquals(SyncAge.Hours(1), ago(60, TimeUnit.MINUTES))
        assertEquals(SyncAge.Hours(23), ago(23, TimeUnit.HOURS))
        assertEquals(SyncAge.Days(1), ago(24, TimeUnit.HOURS))
        assertEquals(SyncAge.Days(10), ago(10, TimeUnit.DAYS))
    }
}
