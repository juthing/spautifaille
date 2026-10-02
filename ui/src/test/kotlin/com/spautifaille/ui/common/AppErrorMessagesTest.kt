package com.spautifaille.ui.common

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorMessagesTest {

    private val allErrors: List<AppError> = listOf(
        AppError.Unavailable,
        AppError.AgeRestricted,
        AppError.GeoBlocked,
        AppError.PaidContent,
        AppError.BotDetected,
        AppError.Network,
        AppError.StreamExpired,
        AppError.NoAudioStream,
        AppError.ExtractionBroken("détail"),
        AppError.MicrophoneUnavailable,
        AppError.RecognitionUnavailable,
        AppError.Unknown("détail"),
    )

    @Test
    fun `chaque AppError correspond a une ressource chaine valide et distincte`() {
        val ids = allErrors.map { it.toMessage() }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals("Chaque erreur doit avoir son propre message", allErrors.size, ids.toSet().size)
    }

    @Test
    fun `le detail d une erreur inconnue ne change pas le message`() {
        assertEquals(AppError.Unknown("a").toMessage(), AppError.Unknown(null).toMessage())
        assertEquals(AppError.ExtractionBroken("a").toMessage(), AppError.ExtractionBroken(null).toMessage())
    }

    @Test
    fun `toAppError extrait l erreur d une AppException`() {
        val error = AppError.GeoBlocked
        assertSame(error, AppException(error).toAppError())
    }

    @Test
    fun `toAppError enveloppe les autres exceptions dans Unknown`() {
        val mapped = IllegalStateException("boom").toAppError()
        assertTrue(mapped is AppError.Unknown)
        assertEquals("boom", (mapped as AppError.Unknown).detail)
    }
}
