package com.spautifaille.data.newpipe

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class OkHttpDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: OkHttpDownloader

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = OkHttpDownloader(OkHttpClient())
    }

    @After fun tearDown() {
        server.close()
    }

    private fun ok(body: String = "ok") = MockResponse.Builder().code(200).body(body).build()

    @Test fun sendsFirefoxUserAgentByDefault() {
        server.enqueue(ok())
        val response = downloader.get(server.url("/").toString())
        assertEquals(200, response.responseCode())
        assertEquals("ok", response.responseBody())
        val recorded = server.takeRequest()
        assertEquals(OkHttpDownloader.USER_AGENT, recorded.headers["User-Agent"])
        assertTrue(OkHttpDownloader.USER_AGENT.contains("Firefox"))
    }

    @Test fun requestHeadersReplaceDefaultUserAgent() {
        server.enqueue(ok())
        val request = Request.newBuilder()
            .get(server.url("/").toString())
            .headers(mapOf("User-Agent" to listOf("VisionOS-UA"), "X-Test" to listOf("a", "b")))
            .build()
        downloader.execute(request)
        val recorded = server.takeRequest()
        assertEquals(listOf("VisionOS-UA"), recorded.headers.values("User-Agent"))
        assertEquals(listOf("a", "b"), recorded.headers.values("X-Test"))
    }

    @Test fun requestCookieHeaderReplacesDefaultCookie() {
        downloader.setCookie(OkHttpDownloader.RECAPTCHA_COOKIES_KEY, "a=1")
        server.enqueue(ok())
        val request = Request.newBuilder()
            .get(server.url("/").toString())
            .headers(mapOf("Cookie" to listOf("custom=1")))
            .build()
        downloader.execute(request)
        assertEquals(listOf("custom=1"), server.takeRequest().headers.values("Cookie"))
    }

    @Test fun http429ThrowsReCaptchaException() {
        server.enqueue(MockResponse.Builder().code(429).body("slow down").build())
        val url = server.url("/").toString()
        val e = assertThrows(ReCaptchaException::class.java) { downloader.get(url) }
        assertEquals("reCaptcha Challenge requested", e.message)
        assertEquals(url, e.url)
    }

    @Test fun non2xxResponsesAreReturnedNotThrown() {
        server.enqueue(MockResponse.Builder().code(404).body("not here").build())
        server.enqueue(MockResponse.Builder().code(403).body("forbidden").build())
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        val url = server.url("/x").toString()
        val r404 = downloader.get(url)
        assertEquals(404, r404.responseCode())
        assertEquals("not here", r404.responseBody())
        assertEquals(403, downloader.get(url).responseCode())
        assertEquals(500, downloader.get(url).responseCode())
    }

    @Test fun postBodyIsSent() {
        server.enqueue(ok("posted"))
        val url = server.url("/api").toString()
        val payload = """{"q":"hello"}""".toByteArray()
        val response = downloader.postWithContentTypeJson(url, emptyMap(), payload)
        assertEquals("posted", response.responseBody())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("""{"q":"hello"}""", recorded.body?.utf8())
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test fun headRequestHasNoBody() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "10").build())
        val response = downloader.head(server.url("/").toString())
        assertEquals(200, response.responseCode())
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test fun latestUrlFollowsRedirects() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/final").build())
        server.enqueue(ok("final"))
        val response = downloader.get(server.url("/start").toString())
        assertEquals("final", response.responseBody())
        assertTrue(response.latestUrl().endsWith("/final"))
    }

    @Test fun gzipResponsesAreDecompressed() {
        val raw = Buffer()
        GzipSink(raw).buffer().use { it.writeUtf8("compressed body") }
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Encoding", "gzip").body(raw).build(),
        )
        val response = downloader.get(server.url("/").toString())
        assertEquals("compressed body", response.responseBody())
        assertTrue(server.takeRequest().headers["Accept-Encoding"]!!.contains("gzip"))
    }

    @Test fun restrictedModeCookieIsOffByDefaultAndYoutubeOnly() {
        assertEquals("", downloader.getCookies("https://www.youtube.com/watch"))
        downloader.updateYoutubeRestrictedModeCookies(true)
        assertEquals("PREF=f2=8000000", downloader.getCookies("https://www.youtube.com/watch"))
        assertEquals("", downloader.getCookies("https://example.com/"))
        downloader.updateYoutubeRestrictedModeCookies(false)
        assertEquals("", downloader.getCookies("https://www.youtube.com/watch"))
    }

    @Test fun cookiesAreMergedAndDeduplicated() {
        downloader.updateYoutubeRestrictedModeCookies(true)
        downloader.setCookie(OkHttpDownloader.RECAPTCHA_COOKIES_KEY, "PREF=f2=8000000; GOOGLE_ABUSE=1")
        assertEquals("PREF=f2=8000000; GOOGLE_ABUSE=1", downloader.getCookies("https://www.youtube.com/"))
    }

    @Test fun cookieHeaderIsSentToYoutubeOnlyWhenEnabled() {
        // Le serveur local n'est pas youtube.com : seul le cookie reCAPTCHA est envoyé.
        downloader.updateYoutubeRestrictedModeCookies(true)
        server.enqueue(ok())
        downloader.get(server.url("/").toString())
        assertNull(server.takeRequest().headers["Cookie"])
    }
}
