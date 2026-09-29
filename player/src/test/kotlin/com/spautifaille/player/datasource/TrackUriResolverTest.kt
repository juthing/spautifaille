package com.spautifaille.player.datasource

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.repository.DownloadRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TrackUriResolverTest {

    private val stableUri = TrackUri.build("abc123")
    private val resolver = mockk<StreamResolver>()
    private val downloads = mockk<DownloadRepository>()

    private fun stream(dash: String? = null) = ResolvedStream(
        videoId = "abc123",
        url = "https://rr1.googlevideo.com/videoplayback?id=abc123&c=VISIONOS",
        mimeType = "audio/webm",
        codec = "opus",
        bitrate = 136_000,
        contentLength = 1_000,
        headers = mapOf("User-Agent" to "VisionOS-UA"),
        expiresAtMs = Long.MAX_VALUE,
        dashManifest = dash,
    )

    @Test
    fun `track uri parsing`() {
        assertEquals("abc123", TrackUri.videoId(stableUri))
        assertNull(TrackUri.videoId(Uri.parse("https://example.com/track/abc")))
        assertNull(TrackUri.videoId(Uri.parse("spautifaille://other/abc")))
        assertNull(TrackUri.videoId(null))
    }

    @Test
    fun `downloaded file has priority over the network`() {
        val file = File.createTempFile("track", ".webm").apply { deleteOnExit() }
        every { downloads.localFileBlocking("abc123") } returns file.absolutePath
        val target = TrackUriResolver(resolver, downloads)

        val resolved = target.resolveDataSpec(DataSpec(stableUri))

        assertEquals(Uri.fromFile(file), resolved.uri)
        assertEquals(stableUri.toString(), resolved.key)
        verify(exactly = 0) { resolver.resolveBlocking(any()) }
    }

    @Test
    fun `missing local file falls back to streaming`() {
        every { downloads.localFileBlocking("abc123") } returns "/does/not/exist.webm"
        every { resolver.resolveBlocking("abc123") } returns stream()

        val resolved = TrackUriResolver(resolver, downloads).resolveDataSpec(DataSpec(stableUri))

        assertEquals("https", resolved.uri.scheme)
    }

    @Test
    fun `streaming uses the resolved url headers and the stable cache key`() {
        every { downloads.localFileBlocking("abc123") } returns null
        every { resolver.resolveBlocking("abc123") } returns stream()
        val original = DataSpec.Builder()
            .setUri(stableUri)
            .setPosition(4_096)
            .setLength(1_024)
            .setHttpRequestHeaders(mapOf("X-Test" to "1"))
            .build()

        val resolved = TrackUriResolver(resolver, downloads).resolveDataSpec(original)

        assertEquals("https://rr1.googlevideo.com/videoplayback?id=abc123&c=VISIONOS", resolved.uri.toString())
        assertEquals(stableUri.toString(), resolved.key)
        assertEquals(mapOf("X-Test" to "1", "User-Agent" to "VisionOS-UA"), resolved.httpRequestHeaders)
        assertEquals(4_096L, resolved.position)
        assertEquals(1_024L, resolved.length)
    }

    @Test
    fun `non track uris pass through untouched`() {
        val spec = DataSpec(Uri.parse("https://example.com/a.mp3"))
        assertSame(spec, TrackUriResolver(resolver, downloads).resolveDataSpec(spec))
    }

    @Test
    fun `dash manifests are rejected with NoAudioStream`() {
        every { downloads.localFileBlocking(any()) } returns null
        every { resolver.resolveBlocking("abc123") } returns stream(dash = "<MPD/>")
        try {
            TrackUriResolver(resolver, downloads).resolveDataSpec(DataSpec(stableUri))
            fail("expected exception")
        } catch (e: StreamResolutionException) {
            assertEquals(AppError.NoAudioStream, e.appError)
        }
    }

    @Test
    fun `resolution failures propagate`() {
        every { downloads.localFileBlocking(any()) } returns null
        every { resolver.resolveBlocking(any()) } throws StreamResolutionException(AppError.Unavailable)
        try {
            TrackUriResolver(resolver, downloads).resolveDataSpec(DataSpec(stableUri))
            fail("expected exception")
        } catch (e: StreamResolutionException) {
            assertEquals(AppError.Unavailable, e.appError)
        }
    }
}
