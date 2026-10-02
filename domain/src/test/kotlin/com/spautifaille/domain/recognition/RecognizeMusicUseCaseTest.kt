package com.spautifaille.domain.recognition

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizeMusicUseCaseTest {

    private val track = RecognizedTrack(title = "Papaoutai", artist = "Stromae", album = "Racine carrée")

    /** Micro simulé : [seconds] secondes en blocs de 100 ms (valeur = numéro du bloc), puis attend/termine. */
    private class FakeCapture(private val seconds: Int = 30, private val failWith: AppError? = null) : AudioCapture {
        var started = false
        var released = false

        override fun record(): Flow<ShortArray> = flow {
            started = true
            try {
                failWith?.let { throw AppException(it) }
                repeat(seconds * 10) {
                    emit(ShortArray(1_600) { 1 })
                }
            } finally {
                released = true
            }
        }
    }

    private class FakeRecognizer(private val results: List<RecognizedTrack?>, private val error: AppError? = null) : MusicRecognizer {
        val sizes = mutableListOf<Int>()
        override suspend fun recognize(pcm: ShortArray, sampleRate: Int): RecognizedTrack? {
            sizes += pcm.size
            error?.let { throw AppException(it) }
            return results[(sizes.size - 1).coerceAtMost(results.lastIndex)]
        }
    }

    @Test
    fun `tentatives a 4, 8 et 12 secondes puis NoMatch`() = runTest {
        val recognizer = FakeRecognizer(listOf(null))
        val capture = FakeCapture()
        val events = RecognizeMusicUseCase(capture, recognizer)().toList()

        assertEquals(listOf(64_000, 128_000, 192_000), recognizer.sizes)
        assertEquals(RecognitionProgress.NoMatch, events.last())
        assertTrue(events.contains(RecognitionProgress.Identifying))
        assertTrue(capture.released)
    }

    @Test
    fun `s'arrete des la premiere correspondance`() = runTest {
        val recognizer = FakeRecognizer(listOf(null, track))
        val capture = FakeCapture()
        val events = RecognizeMusicUseCase(capture, recognizer)().toList()

        assertEquals(listOf(64_000, 128_000), recognizer.sizes)
        assertEquals(RecognitionProgress.Success(track), events.last())
        assertTrue(events.none { it == RecognitionProgress.NoMatch })
        assertTrue(capture.released)
    }

    @Test
    fun `correspondance des la premiere tentative`() = runTest {
        val events = RecognizeMusicUseCase(FakeCapture(), FakeRecognizer(listOf(track)))().toList()
        assertEquals(RecognitionProgress.Success(track), events.last())
        // Identifying n'est émis que pour la dernière tentative.
        assertTrue(events.none { it == RecognitionProgress.Identifying })
    }

    @Test
    fun `la progression d'ecoute est croissante et bornee`() = runTest {
        val events = RecognizeMusicUseCase(FakeCapture(), FakeRecognizer(listOf(null)))().toList()
        val listening = events.filterIsInstance<RecognitionProgress.Listening>()
        assertEquals(0L, listening.first().elapsedMs)
        assertEquals(listening.map { it.elapsedMs }.sorted(), listening.map { it.elapsedMs })
        assertTrue(listening.all { it.totalMs == 12_000L && it.elapsedMs <= 12_000L })
    }

    @Test
    fun `une erreur du micro est propagee`() = runTest {
        RecognizeMusicUseCase(FakeCapture(failWith = AppError.MicrophoneUnavailable), FakeRecognizer(listOf(null)))().test {
            assertTrue(awaitItem() is RecognitionProgress.Listening)
            val error = awaitError()
            assertEquals(AppError.MicrophoneUnavailable, (error as AppException).error)
        }
    }

    @Test
    fun `une erreur reseau du moteur est propagee et le micro libere`() = runTest {
        val capture = FakeCapture()
        RecognizeMusicUseCase(capture, FakeRecognizer(listOf(null), error = AppError.Network))().test {
            var error: Throwable? = null
            while (error == null) {
                when (val e = awaitEvent()) {
                    is app.cash.turbine.Event.Error -> error = e.throwable
                    else -> Unit
                }
            }
            assertEquals(AppError.Network, (error as AppException).error)
        }
        assertTrue(capture.released)
    }

    @Test
    fun `annuler la collecte libere le micro`() = runTest {
        val capture = FakeCapture()
        RecognizeMusicUseCase(capture, FakeRecognizer(listOf(null)))().test {
            awaitItem()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(capture.started)
        assertTrue(capture.released)
    }
}
