package com.spautifaille.player.error

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.spautifaille.domain.error.AppError
import com.spautifaille.player.datasource.StreamResolutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class PlaybackErrorPolicyTest {

    private val fresh = ErrorContext(reResolveAttempts = 0, networkAttempts = 0, hasNext = true)

    private fun http(code: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code, "msg", null, emptyMap(), DataSpec(Uri.parse("https://x.googlevideo.com/v")), ByteArray(0),
        )

    private fun error(cause: Throwable?, code: Int = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) =
        PlaybackException("test", cause, code)

    @Test
    fun `403 and 410 re-resolve twice then skip`() {
        listOf(403, 410).forEach { status ->
            val e = error(http(status))
            assertEquals(ErrorAction.ReResolve, PlaybackErrorPolicy.decide(e, fresh))
            assertEquals(ErrorAction.ReResolve, PlaybackErrorPolicy.decide(e, fresh.copy(reResolveAttempts = 1)))
            assertEquals(
                ErrorAction.Skip(AppError.StreamExpired),
                PlaybackErrorPolicy.decide(e, fresh.copy(reResolveAttempts = 2)),
            )
            assertEquals(
                ErrorAction.Fail(AppError.StreamExpired),
                PlaybackErrorPolicy.decide(e, fresh.copy(reResolveAttempts = 2, hasNext = false)),
            )
        }
    }

    @Test
    fun `404 is unrecoverable`() {
        assertEquals(ErrorAction.Skip(AppError.Unavailable), PlaybackErrorPolicy.decide(error(http(404)), fresh))
        assertEquals(
            ErrorAction.Fail(AppError.Unavailable),
            PlaybackErrorPolicy.decide(error(http(404)), fresh.copy(hasNext = false)),
        )
    }

    @Test
    fun `429 is treated as bot detection with a bounded retry`() {
        val e = error(http(429))
        assertTrue(PlaybackErrorPolicy.decide(e, fresh) is ErrorAction.Retry)
        assertEquals(
            ErrorAction.Skip(AppError.BotDetected),
            PlaybackErrorPolicy.decide(e, fresh.copy(networkAttempts = PlaybackErrorPolicy.MAX_BOT_ATTEMPTS)),
        )
    }

    @Test
    fun `server errors are retried like network errors`() {
        assertTrue(PlaybackErrorPolicy.decide(error(http(503)), fresh) is ErrorAction.Retry)
    }

    @Test
    fun `network errors retry with exponential backoff capped at 30 seconds`() {
        val e = error(UnknownHostException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val delays = (0 until PlaybackErrorPolicy.MAX_NETWORK_ATTEMPTS).map {
            (PlaybackErrorPolicy.decide(e, fresh.copy(networkAttempts = it)) as ErrorAction.Retry).delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays)
        assertEquals(30_000L, PlaybackErrorPolicy.backoffMs(50))
        assertEquals(
            ErrorAction.Fail(AppError.Network),
            PlaybackErrorPolicy.decide(e, fresh.copy(networkAttempts = PlaybackErrorPolicy.MAX_NETWORK_ATTEMPTS)),
        )
    }

    @Test
    fun `timeouts are network errors`() {
        val e = error(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(ErrorKind.Network, PlaybackErrorPolicy.classify(e))
    }

    @Test
    fun `unspecified io error caused by a socket failure is a network error`() {
        val e = error(java.net.SocketTimeoutException("slow"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(ErrorKind.Network, PlaybackErrorPolicy.classify(e))
        val other = error(IOException("disk"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertTrue(PlaybackErrorPolicy.classify(other) is ErrorKind.Fatal)
    }

    @Test
    fun `resolver failures are unwrapped from the cause chain`() {
        fun wrapped(app: AppError) = error(IOException("loader", StreamResolutionException(app)), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        assertEquals(ErrorAction.Skip(AppError.Unavailable), PlaybackErrorPolicy.decide(wrapped(AppError.Unavailable), fresh))
        assertEquals(ErrorAction.Skip(AppError.AgeRestricted), PlaybackErrorPolicy.decide(wrapped(AppError.AgeRestricted), fresh))
        assertEquals(ErrorAction.Skip(AppError.NoAudioStream), PlaybackErrorPolicy.decide(wrapped(AppError.NoAudioStream), fresh))
        assertEquals(ErrorAction.Fail(AppError.GeoBlocked), PlaybackErrorPolicy.decide(wrapped(AppError.GeoBlocked), fresh.copy(hasNext = false)))
        assertEquals(ErrorAction.ReResolve, PlaybackErrorPolicy.decide(wrapped(AppError.StreamExpired), fresh))
        assertTrue(PlaybackErrorPolicy.decide(wrapped(AppError.Network), fresh) is ErrorAction.Retry)
        assertTrue(PlaybackErrorPolicy.decide(wrapped(AppError.BotDetected), fresh) is ErrorAction.Retry)
        assertEquals(
            ErrorAction.Skip(AppError.ExtractionBroken("x")),
            PlaybackErrorPolicy.decide(wrapped(AppError.ExtractionBroken("x")), fresh),
        )
    }

    @Test
    fun `missing downloaded file falls back to re-resolution`() {
        val e = error(java.io.FileNotFoundException(), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertEquals(ErrorAction.ReResolve, PlaybackErrorPolicy.decide(e, fresh))
    }

    @Test
    fun `parsing and decoding errors skip the track`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        ).forEach { code ->
            val action = PlaybackErrorPolicy.decide(error(null, code), fresh)
            assertTrue("code $code -> $action", action is ErrorAction.Skip)
        }
    }
}
