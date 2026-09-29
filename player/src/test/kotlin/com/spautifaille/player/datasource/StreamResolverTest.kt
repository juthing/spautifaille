package com.spautifaille.player.datasource

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamResolverTest {

    private var now = 1_000_000L
    private var quality = AudioQuality.BEST
    private var counter = 0

    private val streams = mockk<StreamRepository>()
    private val settings = mockk<SettingsRepository>().apply {
        coEvery { current() } answers { AppSettings(audioQuality = quality) }
    }
    private val resolver = StreamResolver(streams, settings) { now }

    private fun stream(id: String, expiresInMs: Long = 6 * 3_600_000L) = ResolvedStream(
        videoId = id,
        url = "https://rr1.googlevideo.com/videoplayback?id=$id&n=${counter++}",
        mimeType = "audio/webm",
        codec = "opus",
        bitrate = 136_000,
        contentLength = null,
        headers = mapOf("User-Agent" to "VisionOS"),
        expiresAtMs = now + expiresInMs,
    )

    private fun stubResolve() {
        coEvery { streams.resolveAudio(any(), any()) } answers { stream(firstArg()) }
    }

    @Test
    fun `second call is served from the cache`() = runTest {
        stubResolve()
        val first = resolver.resolve("abc")
        now += 60_000
        val second = resolver.resolve("abc")
        assertSame(first, second)
        coVerify(exactly = 1) { streams.resolveAudio("abc", AudioQuality.BEST) }
    }

    @Test
    fun `entries never live longer than 30 minutes`() = runTest {
        stubResolve()
        resolver.resolve("abc")
        now += StreamResolver.MAX_TTL_MS - 1
        resolver.resolve("abc")
        coVerify(exactly = 1) { streams.resolveAudio(any(), any()) }
        now += 2
        resolver.resolve("abc")
        coVerify(exactly = 2) { streams.resolveAudio(any(), any()) }
    }

    @Test
    fun `entries expire before the stream expiry`() = runTest {
        coEvery { streams.resolveAudio(any(), any()) } answers { stream(firstArg(), expiresInMs = 5 * 60_000L) }
        resolver.resolve("abc")
        now += 5 * 60_000L - StreamResolver.EXPIRY_MARGIN_MS + 1
        resolver.resolve("abc")
        coVerify(exactly = 2) { streams.resolveAudio(any(), any()) }
    }

    @Test
    fun `an already expired stream is never reused`() = runTest {
        coEvery { streams.resolveAudio(any(), any()) } answers { stream(firstArg(), expiresInMs = -1) }
        resolver.resolve("abc")
        resolver.resolve("abc")
        coVerify(exactly = 2) { streams.resolveAudio(any(), any()) }
    }

    @Test
    fun `invalidate forces a new resolution of that id only`() = runTest {
        stubResolve()
        val first = resolver.resolve("abc")
        resolver.resolve("other")
        resolver.invalidate("abc")
        val second = resolver.resolve("abc")
        resolver.resolve("other")
        assertTrue(first !== second)
        coVerify(exactly = 2) { streams.resolveAudio("abc", any()) }
        coVerify(exactly = 1) { streams.resolveAudio("other", any()) }
    }

    @Test
    fun `changing the audio quality bypasses the cache`() = runTest {
        stubResolve()
        resolver.resolve("abc")
        quality = AudioQuality.DATA_SAVER
        resolver.resolve("abc")
        coVerify(exactly = 1) { streams.resolveAudio("abc", AudioQuality.BEST) }
        coVerify(exactly = 1) { streams.resolveAudio("abc", AudioQuality.DATA_SAVER) }
    }

    @Test
    fun `AppException is wrapped in StreamResolutionException`() = runTest {
        coEvery { streams.resolveAudio(any(), any()) } throws AppException(AppError.AgeRestricted)
        try {
            resolver.resolve("abc")
            fail("expected exception")
        } catch (e: StreamResolutionException) {
            assertEquals(AppError.AgeRestricted, e.appError)
        }
        // Un échec n'est pas mis en cache.
        stubResolve()
        resolver.resolve("abc")
    }

    @Test
    fun `IO failures map to a network error`() = runTest {
        coEvery { streams.resolveAudio(any(), any()) } throws java.io.IOException("offline")
        try {
            resolver.resolve("abc")
            fail("expected exception")
        } catch (e: StreamResolutionException) {
            assertEquals(AppError.Network, e.appError)
        }
    }

    @Test
    fun `cache key is stable per id and separated by quality`() {
        val best = resolver.cacheKey("abc")
        assertEquals(best, resolver.cacheKey("abc"))
        assertEquals("abc#BEST", best)
    }
}
