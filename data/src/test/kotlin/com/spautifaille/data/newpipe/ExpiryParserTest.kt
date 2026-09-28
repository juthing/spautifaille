package com.spautifaille.data.newpipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

class ExpiryParserTest {

    private val base = "https://rr1---sn-abc.googlevideo.com/videoplayback"

    @Test fun parsesExpireQueryParameter() {
        val url = "$base?expire=1893456000&ei=x&itag=251&c=WEB"
        assertEquals(1_893_456_000_000L, StreamUrls.parseExpireMs(url))
    }

    @Test fun parsesExpireInMiddleOfQuery() {
        val url = "$base?id=o-A&expire=1893456000&itag=140"
        assertEquals(1_893_456_000_000L, StreamUrls.parseExpireMs(url))
    }

    @Test fun parsesExpireFromEscapedDashManifest() {
        val manifest = """<BaseURL>$base?id=x&amp;expire=1893456000&amp;c=VISIONOS</BaseURL>"""
        assertEquals(1_893_456_000_000L, StreamUrls.parseExpireMs(manifest))
    }

    @Test fun parsesPathStyleExpire() {
        assertEquals(1_893_456_000_000L, StreamUrls.parseExpireMs("$base/expire/1893456000/itag/251"))
    }

    @Test fun ignoresLookalikeParameters() {
        assertNull(StreamUrls.parseExpireMs("$base?notexpire=1893456000"))
        assertNull(StreamUrls.parseExpireMs("$base?expire=abc"))
        assertNull(StreamUrls.parseExpireMs("$base?expire=123"))
    }

    @Test fun expiryHasSixtySecondsSafetyMargin() {
        val url = "$base?expire=1893456000"
        assertEquals(1_893_456_000_000L - 60_000L, StreamUrls.expiresAtMs(url, nowMs = 5L))
    }

    @Test fun fallsBackToFiveHoursWhenNoExpire() {
        val now = 1_000_000L
        assertEquals(now + 5 * 60 * 60 * 1000L, StreamUrls.expiresAtMs("$base?itag=251", now))
    }

    @Test fun detectsVisionOs() {
        assertTrue(StreamUrls.isVisionOs("$base?expire=1&c=VISIONOS&itag=251"))
        assertTrue(StreamUrls.isVisionOs("<BaseURL>$base?id=1&amp;c=VISIONOS</BaseURL>"))
        assertFalse(StreamUrls.isVisionOs("$base?c=WEB"))
        assertFalse(StreamUrls.isVisionOs("$base?itag=251"))
    }

    @Test fun detectsWeb() {
        assertTrue(StreamUrls.isWeb("$base?expire=1&c=WEB&itag=251"))
        assertTrue(StreamUrls.isWeb("$base?c=WEB_EMBEDDED_PLAYER"))
        assertFalse(StreamUrls.isWeb("$base?c=VISIONOS"))
        assertFalse(StreamUrls.isWeb("$base?c=ANDROID"))
        assertFalse(StreamUrls.isWeb("$base?itag=251"))
    }

    @Test fun agreesWithNewPipeDetection() {
        val urls = listOf(
            "$base?expire=1&c=VISIONOS&itag=251",
            "$base?expire=1&c=WEB&itag=251",
            "$base?expire=1&c=ANDROID&itag=251",
            "$base?expire=1&itag=251",
        )
        for (url in urls) {
            assertEquals(url, YoutubeParsingHelper.isVisionOsStreamingUrl(url), StreamUrls.isVisionOs(url))
            assertEquals(url, YoutubeParsingHelper.isWebStreamingUrl(url), StreamUrls.isWeb(url))
        }
    }

    @Test fun visionOsUrlGetsVisionOsUserAgentOnly() {
        val headers = StreamUrls.headersFor("$base?c=VISIONOS", "FF") { "VISION-UA" }
        assertEquals(mapOf("User-Agent" to "VISION-UA"), headers)
    }

    @Test fun webUrlGetsOriginAndReferer() {
        val headers = StreamUrls.headersFor("$base?c=WEB", "FF") { error("not VisionOS") }
        assertEquals("FF", headers["User-Agent"])
        assertEquals("https://www.youtube.com", headers["Origin"])
        assertEquals("https://www.youtube.com", headers["Referer"])
    }

    @Test fun otherUrlGetsDefaultUserAgent() {
        val headers = StreamUrls.headersFor("$base?c=ANDROID", "FF") { error("not VisionOS") }
        assertEquals(mapOf("User-Agent" to "FF"), headers)
    }

    @Test fun realVisionOsUserAgentIsProvidedByNewPipe() {
        val ua = YoutubeParsingHelper.getVisionOsUserAgent(null)
        assertTrue(ua, ua.contains("visionos", ignoreCase = true))
    }
}
