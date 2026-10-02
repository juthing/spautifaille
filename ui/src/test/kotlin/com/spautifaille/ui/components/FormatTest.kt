package com.spautifaille.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class FormatTest {
    @Test
    fun `formatDuration formate minutes et secondes`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("3:05", formatDuration(185_000))
        assertEquals("12:00", formatDuration(720_000))
    }

    @Test
    fun `formatDuration ajoute les heures au dela d une heure`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("2:03:04", formatDuration(7_384_000))
    }

    @Test
    fun `formatDuration borne les valeurs negatives`() {
        assertEquals("0:00", formatDuration(-5_000))
    }

    @Test
    fun `formatUploadDate formate en francais`() {
        assertEquals("12 mars 2021", formatUploadDate(LocalDate.of(2021, 3, 12), Locale.FRANCE))
        assertEquals("1 janvier 2020", formatUploadDate(LocalDate.of(2020, 1, 1), Locale.FRANCE))
    }
}
