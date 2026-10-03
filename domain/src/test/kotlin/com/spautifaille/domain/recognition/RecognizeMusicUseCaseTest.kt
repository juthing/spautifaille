package com.spautifaille.domain.recognition

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
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

    /**
     * Chemin réel : micro sur un autre thread (comme `flowOn(IO)`), collecte sur un autre thread (comme le thread
     * principal), moteur lent, correspondance à la 1re, 2e ou 3e tentative. Aucune exception ne doit sortir du flux
     * quand le titre est reconnu, et le micro est libéré.
     */
    @Test
    fun `reconnaissance reussie avec micro et collecte sur des threads differents`() {
        for (matchAt in 1..3) {
            repeat(8) { iteration ->
                var released = false
                val capture = object : AudioCapture {
                    override fun record(): Flow<ShortArray> = flow {
                        try {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                Thread.sleep(1)
                                emit(ShortArray(1_600) { 1 })
                            }
                        } finally {
                            released = true
                        }
                    }.flowOn(Dispatchers.IO)
                }
                val recognizer = object : MusicRecognizer {
                    var calls = 0
                    override suspend fun recognize(pcm: ShortArray, sampleRate: Int): RecognizedTrack? {
                        calls++
                        delay(iteration * 2L)
                        return track.takeIf { calls == matchAt }
                    }
                }
                val events = mutableListOf<RecognitionProgress>()
                runBlocking(Dispatchers.Default) {
                    RecognizeMusicUseCase(capture, recognizer)().collect { events += it }
                }
                assertEquals(RecognitionProgress.Success(track), events.last())
                assertEquals(matchAt, recognizer.calls)
                assertTrue(released)
            }
        }
    }

    @Test
    fun `une exception inattendue du moteur est propagee telle quelle et le micro libere`() = runTest {
        val capture = FakeCapture()
        val recognizer = object : MusicRecognizer {
            override suspend fun recognize(pcm: ShortArray, sampleRate: Int): RecognizedTrack? =
                throw IllegalStateException("inattendu")
        }
        val error = runCatching { RecognizeMusicUseCase(capture, recognizer)().toList() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
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
