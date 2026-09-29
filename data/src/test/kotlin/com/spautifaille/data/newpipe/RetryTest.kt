package com.spautifaille.data.newpipe

import com.spautifaille.domain.error.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {

    // Même politique que NewPipeStreamRepository.call : réseau seulement (BotDetected est laissé aux appelants).
    private val retryable: (Throwable) -> Boolean = { NewPipeErrorMapper.mapToError(it) is AppError.Network }

    @Test
    fun retriesOnNetworkThenSucceeds() = runTest {
        var calls = 0
        val result = retryWithBackoff(shouldRetry = retryable) {
            calls++
            if (calls < 3) throw IOException("down")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        // 500 ms + 1000 ms de backoff en temps virtuel.
        assertEquals(1_500L, currentTime)
    }

    @Test
    fun givesUpAfterTimesAttemptsAndRethrowsLastError() = runTest {
        var calls = 0
        val error = runCatching {
            retryWithBackoff(times = 4, shouldRetry = retryable) {
                calls++
                throw IOException("attempt $calls")
            }
        }.exceptionOrNull()
        assertEquals(4, calls)
        assertTrue(error is IOException)
        assertEquals("attempt 4", error!!.message)
    }

    @Test
    fun backoffDelaysGrowByFactor() = runTest {
        var calls = 0
        try {
            retryWithBackoff(times = 4, shouldRetry = retryable) {
                calls++
                throw IOException("down")
            }
        } catch (_: IOException) {
        }
        assertEquals(4, calls)
        // 500 + 1000 + 2000
        assertEquals(3_500L, currentTime)
    }

    @Test
    fun doesNotRetryOnUnavailable() = runTest {
        var calls = 0
        try {
            retryWithBackoff(shouldRetry = retryable) {
                calls++
                throw ContentNotAvailableException("gone")
            }
            throw AssertionError("should have thrown")
        } catch (e: ContentNotAvailableException) {
            assertTrue(e.message!!.contains("gone"))
        }
        assertEquals(1, calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun doesNotRetryBotDetection() = runTest {
        var calls = 0
        try {
            retryWithBackoff(shouldRetry = retryable) {
                calls++
                throw ReCaptchaException("captcha", "https://youtube.com")
            }
            throw AssertionError("should have thrown")
        } catch (_: ReCaptchaException) {
        }
        assertEquals(1, calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun doesNotRetryCancellation() = runTest {
        var calls = 0
        try {
            retryWithBackoff(shouldRetry = { true }) {
                calls++
                throw CancellationException("cancelled")
            }
        } catch (_: CancellationException) {
        }
        assertEquals(1, calls)
    }

    @Test
    fun succeedsImmediatelyWithoutDelay() = runTest {
        assertEquals(42, retryWithBackoff(shouldRetry = retryable) { 42 })
        assertEquals(0L, currentTime)
    }
}
