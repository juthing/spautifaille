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

    @Test
    fun `stage follows the job state and what is left to review`() {
        assertEquals(ImportStage.MATCHING, job(state = ImportJobState.RUNNING).stage())
        assertEquals(ImportStage.FAILED, job(state = ImportJobState.FAILED).stage())
        assertEquals(ImportStage.NEEDS_REVIEW, job(needsReview = 2, notFound = 0).stage())
        assertEquals(ImportStage.NEEDS_REVIEW, job(needsReview = 0, notFound = 3).stage())
        assertEquals(ImportStage.DONE, job(needsReview = 0, notFound = 0).stage())
    }

    @Test
    fun `step states always start with a finished reading step`() {
        ImportStage.entries.forEach { assertEquals(StepState.DONE, stepStates(it).first()) }
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.PENDING), stepStates(ImportStage.MATCHING))
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.CURRENT), stepStates(ImportStage.NEEDS_REVIEW))
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE), stepStates(ImportStage.DONE))
        assertEquals(listOf(StepState.DONE, StepState.FAILED, StepState.PENDING), stepStates(ImportStage.FAILED))
    }

    @Test
    fun `haptic feedback only fires when a running job finishes`() {
        assertEquals(JobFeedback.CONFIRM, jobFeedback(ImportJobState.RUNNING, ImportJobState.COMPLETED))
        assertEquals(JobFeedback.REJECT, jobFeedback(ImportJobState.RUNNING, ImportJobState.FAILED))
        assertEquals(null, jobFeedback(ImportJobState.RUNNING, ImportJobState.RUNNING))
        assertEquals(null, jobFeedback(null, ImportJobState.COMPLETED))
        assertEquals(null, jobFeedback(ImportJobState.COMPLETED, ImportJobState.COMPLETED))
        assertEquals(null, jobFeedback(ImportJobState.FAILED, ImportJobState.FAILED))
    }
}
