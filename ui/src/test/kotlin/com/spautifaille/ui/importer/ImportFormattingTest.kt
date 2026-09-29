package com.spautifaille.ui.importer

import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportedTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportFormattingTest {

    @Test
    fun `subtitle joins artists and duration`() {
        assertEquals("A, B · 3:25", ImportedTrack("t", listOf("A", "B"), durationMs = 205_000).subtitle())
        assertEquals("A", ImportedTrack("t", listOf("A")).subtitle())
        assertEquals("3:25", ImportedTrack("t", durationMs = 205_000).subtitle())
        assertEquals("", ImportedTrack("t", listOf(" "), durationMs = 0).subtitle())
    }

    @Test
    fun `score percent is rounded and clamped`() {
        assertEquals(93, scorePercent(0.926))
        assertEquals(100, scorePercent(1.2))
        assertEquals(0, scorePercent(-0.1))
    }

    @Test
    fun `job progress is a fraction of processed titles`() {
        assertEquals(0.25f, job(state = ImportJobState.RUNNING, total = 8, processed = 2).progress, 0.0001f)
        assertEquals(1f, job(total = 0, processed = 0).progress, 0.0001f)
    }
}
