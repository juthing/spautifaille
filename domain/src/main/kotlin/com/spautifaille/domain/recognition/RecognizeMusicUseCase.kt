package com.spautifaille.domain.recognition

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/** Avancement d'une session de reconnaissance. */
sealed interface RecognitionProgress {
    /** Micro ouvert ; [elapsedMs] d'audio capturé sur [totalMs] au maximum. */
    data class Listening(val elapsedMs: Long, val totalMs: Long) : RecognitionProgress

    /** Dernière tentative en cours : plus rien à capturer, on attend la réponse du service. */
    data object Identifying : RecognitionProgress

    data class Success(val track: RecognizedTrack) : RecognitionProgress

    data object NoMatch : RecognitionProgress
}

/**
 * Écoute le micro et tente l'identification à intervalles réguliers (par défaut 4 s, 8 s puis 12 s d'audio) :
 * la session s'arrête dès qu'un titre est reconnu. La capture continue pendant qu'une tentative est en cours
 * (les blocs sont mis en file, rien n'est perdu).
 *
 * Les erreurs ([com.spautifaille.domain.error.AppException]) sont propagées dans le flux. Annuler la collecte
 * libère le micro.
 */
class RecognizeMusicUseCase(
    private val capture: AudioCapture,
    private val recognizer: MusicRecognizer,
    private val attemptsAtMs: List<Long> = DEFAULT_ATTEMPTS_MS,
) {
    init {
        require(attemptsAtMs.isNotEmpty() && attemptsAtMs == attemptsAtMs.sorted()) { "Tentatives non triées" }
    }

    val totalMs: Long get() = attemptsAtMs.last()

    operator fun invoke(): Flow<RecognitionProgress> = flow {
        val sampleRate = AudioCapture.SAMPLE_RATE
        val pcm = PcmBuffer()
        var nextAttempt = 0
        var finished = false
        emit(RecognitionProgress.Listening(0, totalMs))
        capture.record()
            .buffer(Channel.UNLIMITED)
            .takeWhile { chunk ->
                pcm.append(chunk)
                val elapsedMs = pcm.size * 1000L / sampleRate
                if (nextAttempt < attemptsAtMs.size && elapsedMs >= attemptsAtMs[nextAttempt]) {
                    val isLast = nextAttempt == attemptsAtMs.lastIndex
                    nextAttempt++
                    if (isLast) emit(RecognitionProgress.Identifying)
                    val track = recognizer.recognize(pcm.toShortArray(), sampleRate)
                    when {
                        track != null -> {
                            emit(RecognitionProgress.Success(track))
                            finished = true
                        }
                        isLast -> {
                            emit(RecognitionProgress.NoMatch)
                            finished = true
                        }
                    }
                }
                if (!finished) emit(RecognitionProgress.Listening(minOf(elapsedMs, totalMs), totalMs))
                !finished
            }
            .collect {}
    }

    companion object {
        val DEFAULT_ATTEMPTS_MS: List<Long> = listOf(4_000L, 8_000L, 12_000L)
    }
}

/** Accumulateur de PCM (tableau qui grandit par doublement). */
internal class PcmBuffer {
    private var data = ShortArray(16_000 * 12)
    var size = 0
        private set

    fun append(chunk: ShortArray) {
        if (size + chunk.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
        chunk.copyInto(data, size)
        size += chunk.size
    }

    fun toShortArray(): ShortArray = data.copyOf(size)
}
