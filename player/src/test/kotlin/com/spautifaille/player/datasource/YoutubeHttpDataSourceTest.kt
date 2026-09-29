package com.spautifaille.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Construction pure des requêtes YouTube + test d'intégration du data source contre un MockWebServer. */
@RunWith(RobolectricTestRunner::class)
class YoutubeHttpDataSourceTest {

    private val base = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&id=xyz&c=VISIONOS&cpn=AbC"

    // --- URL / requête (pur) ---

    @Test
    fun `range and rn parameters are appended`() {
        assertEquals("$base&range=0-65535&rn=1", YoutubeRequests.buildUrl(base, 0, 65_536, 1))
        assertEquals("$base&range=1000-1999&rn=7", YoutubeRequests.buildUrl(base, 1_000, 1_000, 7))
    }

    @Test
    fun `open ended range has no end`() {
        assertEquals("$base&range=4096-&rn=3", YoutubeRequests.buildUrl(base, 4_096, C.LENGTH_UNSET.toLong(), 3))
    }

    @Test
    fun `cpn and existing parameters are kept`() {
        val url = YoutubeRequests.buildUrl(base, 0, 10, 1)
        assertTrue(url.startsWith(base))
        assertTrue(url.contains("cpn=AbC"))
    }

    @Test
    fun `parameters already present are not duplicated`() {
        val with = "$base&range=0-9&rn=5"
        assertEquals(with, YoutubeRequests.buildUrl(with, 100, 50, 9))
    }

    @Test
    fun `url without query string gets a question mark`() {
        assertEquals(
            "https://x.googlevideo.com/videoplayback?range=0-9&rn=1",
            YoutubeRequests.buildUrl("https://x.googlevideo.com/videoplayback", 0, 10, 1),
        )
    }

    @Test
    fun `googlevideo detection`() {
        assertTrue(YoutubeRequests.isGoogleVideoUrl(base))
        assertTrue(YoutubeRequests.isGoogleVideoUrl("https://googlevideo.com/videoplayback"))
        assertFalse(YoutubeRequests.isGoogleVideoUrl("https://www.youtube.com/watch?v=x"))
        assertFalse(YoutubeRequests.isGoogleVideoUrl("https://evilgooglevideo.com/videoplayback"))
        assertFalse(YoutubeRequests.isGoogleVideoUrl("https://example.com/?u=googlevideo.com"))
    }

    @Test
    fun `rewrite builds a POST with the fixed body and moves the range into the url`() {
        val spec = DataSpec.Builder()
            .setUri(base)
            .setPosition(2_048)
            .setLength(512)
            .setHttpRequestHeaders(mapOf("User-Agent" to "VisionOS-UA"))
            .build()

        val rewritten = YoutubeRequests.rewrite(spec, 4)

        assertEquals("$base&range=2048-2559&rn=4", rewritten.uri.toString())
        assertEquals(DataSpec.HTTP_METHOD_POST, rewritten.httpMethod)
        assertArrayEquals(byteArrayOf(0x78, 0x00), rewritten.httpBody)
        assertEquals(0L, rewritten.position)
        assertEquals(C.LENGTH_UNSET.toLong(), rewritten.length)
        assertEquals("VisionOS-UA", rewritten.httpRequestHeaders["User-Agent"])
    }

    // --- Intégration (MockWebServer) ---

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun factory() = YoutubeHttpDataSource.Factory(OkHttpClient()) { true }

    @Test
    fun `request is a POST with range in the url, no Range header, and the resolved user agent`() {
        val payload = ByteArray(1_000) { it.toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())
        val spec = DataSpec.Builder()
            .setUri(server.url("/videoplayback?id=xyz").toString())
            .setPosition(64)
            .setLength(1_000)
            .setHttpRequestHeaders(mapOf("User-Agent" to "VisionOS-UA"))
            .build()

        val source = factory().createDataSource()
        val opened = source.open(spec)
        val read = ByteArray(1_000)
        var total = 0
        while (total < read.size) {
            val n = source.read(read, total, read.size - total)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        source.close()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/videoplayback?id=xyz&range=64-1063&rn=1", request.target)
        assertNull("no Range header expected", request.headers["Range"])
        assertEquals("VisionOS-UA", request.headers["User-Agent"])
        assertArrayEquals(byteArrayOf(0x78, 0x00), request.body?.toByteArray())
        // Le serveur répond 200 avec la plage demandée : aucun octet ne doit être « sauté » côté client.
        assertEquals(1_000L, opened)
        assertEquals(1_000, total)
        assertArrayEquals(payload, read)
    }

    @Test
    fun `request counter increments on each open`() {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body("ab").build()) }
        val source = factory().createDataSource()
        repeat(2) {
            source.open(DataSpec(Uri.parse(server.url("/videoplayback?id=xyz").toString())))
            source.close()
        }
        assertTrue(server.takeRequest().target.orEmpty().endsWith("&range=0-&rn=1"))
        assertTrue(server.takeRequest().target.orEmpty().endsWith("&range=0-&rn=2"))
    }

    @Test
    fun `403 raises InvalidResponseCodeException`() {
        server.enqueue(MockResponse.Builder().code(403).body("denied").build())
        val source = factory().createDataSource()
        try {
            source.open(DataSpec(Uri.parse(server.url("/videoplayback?id=xyz").toString())))
            fail("expected exception")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }
    }

    @Test
    fun `non youtube urls are left untouched`() {
        server.enqueue(MockResponse.Builder().code(200).body("hello").build())
        val source = YoutubeHttpDataSource.Factory(OkHttpClient()) { false }.createDataSource()
        source.open(DataSpec(Uri.parse(server.url("/file.mp3").toString())))
        source.close()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/file.mp3", request.target)
    }
}
