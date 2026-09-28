package com.spautifaille.ui.library

import com.spautifaille.domain.model.HistoryEntry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryGroupingTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 9, 28)

    private fun entry(id: Long, date: LocalDate, hour: Int) = HistoryEntry(
        id = id,
        track = track(id.toInt()),
        playedAt = LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli(),
    )

    @Test
    fun `empty history gives no items`() {
        assertTrue(groupHistoryByDay(emptyList(), today, zone).isEmpty())
    }

    @Test
    fun `groups by local day with today and yesterday labels`() {
        val history = listOf(
            entry(1, today, 20),
            entry(2, today, 8),
            entry(3, today.minusDays(1), 23),
            entry(4, today.minusDays(5), 12),
        )

        val items = groupHistoryByDay(history, today, zone)

        val headers = items.filterIsInstance<HistoryListItem.Header>().map { it.day }
        assertEquals(
            listOf(HistoryDay.Today, HistoryDay.Yesterday, HistoryDay.On(today.minusDays(5))),
            headers,
        )
        assertEquals(7, items.size)
        assertTrue(items[0] is HistoryListItem.Header)
        assertEquals(listOf(0, 1, 2, 3), items.filterIsInstance<HistoryListItem.Entry>().map { it.index })
    }

    @Test
    fun `day boundary uses the provided zone`() {
        // 23:30 UTC le 27 = 01:30 le 28 à Paris (UTC+2 en septembre) : « Aujourd'hui ».
        val instant = LocalDateTime.of(2026, 9, 27, 23, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val items = groupHistoryByDay(listOf(HistoryEntry(1, track(1), instant)), today, zone)
        assertEquals(HistoryDay.Today, (items.first() as HistoryListItem.Header).day)
    }
}
