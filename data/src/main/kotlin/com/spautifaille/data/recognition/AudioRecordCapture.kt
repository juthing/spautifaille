package com.spautifaille.data.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.recognition.AudioCapture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Capture micro via [AudioRecord] : 16 kHz mono PCM 16 bits (taux garanti sur tous les appareils), source `MIC`
 * (pas de traitement vocal agressif, contrairement à `VOICE_RECOGNITION`). Blocs de 100 ms.
 *
 * La permission `RECORD_AUDIO` est demandée par l'UI avant la collecte ; si elle manque (retirée entre-temps),
 * l'erreur devient [AppError.MicrophoneUnavailable].
 */
class AudioRecordCapture @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioCapture {

    @SuppressLint("MissingPermission")
    override fun record(): Flow<ShortArray> = flow {
        val sampleRate = AudioCapture.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuffer <= 0) throw AppException(AppError.MicrophoneUnavailable)
        // Tampon interne d'une seconde au moins : absorbe les à-coups du consommateur.
        val bufferBytes = maxOf(minBuffer, sampleRate * 2)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, CHANNEL, ENCODING, bufferBytes)
        } catch (e: SecurityException) {
            throw AppException(AppError.MicrophoneUnavailable, e)
        } catch (e: IllegalArgumentException) {
            throw AppException(AppError.MicrophoneUnavailable, e)
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw AppException(AppError.MicrophoneUnavailable)
            try {
                recorder.startRecording()
            } catch (e: IllegalStateException) {
                throw AppException(AppError.MicrophoneUnavailable, e)
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AppException(AppError.MicrophoneUnavailable)
            }
            val chunk = ShortArray(sampleRate / 10)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(chunk, 0, chunk.size)
                if (read < 0) throw AppException(AppError.MicrophoneUnavailable)
                if (read > 0) emit(chunk.copyOf(read))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(ioDispatcher)

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
