package com.spautifaille.data.recognition

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ShazamMusicRecognizerTest {

    private lateinit var server: MockWebServer
    private lateinit var recognizer: ShazamMusicRecognizer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        recognizer = ShazamMusicRecognizer(
            client = OkHttpClient(),
            defaultDispatcher = Dispatchers.Default,
            endpoint = server.url("/discovery/v5/en/US/android/-/tag").toString().trimEnd('/'),
            clock = { 1_700_000_000_123L },
            timeZoneId = { "Europe/Paris" },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** Quelques salves de 1 kHz : assez de pics pour que la requête parte. */
    private val audio: ShortArray = ShortArray(16_000 * 4) { n ->
        val t = n / 16_000.0
        val envelope = if (t % 0.5 < 0.08) sin(PI * (t % 0.5) / 0.08) else 0.0
        (12_000 * envelope * sin(2 * PI * 1000.0 * t)).toInt().toShort()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `une correspondance donne titre, artiste, album et pochette`() = runTest {
        server.enqueue(json(MATCH_JSON))

        val track = recognizer.recognize(audio, 16_000)

        assertNotNull(track)
        assertEquals("Papaoutai", track!!.title)
        assertEquals("Stromae", track.artist)
        assertEquals("Racine carrée", track.album)
        assertEquals("2013", track.releaseYear)
        assertEquals("Dance", track.genre)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/cover.jpg/400x400cc.jpg", track.artworkUrl)
        assertEquals("Papaoutai Stromae", track.searchQuery)
    }

    @Test
    fun `la requete respecte le format de SongRec`() = runTest {
        server.enqueue(json(NO_MATCH_JSON))

        recognizer.recognize(audio, 16_000)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val path = request.url.encodedPath
        val segments = path.removePrefix("/discovery/v5/en/US/android/-/tag/").split('/')
        assertEquals(2, segments.size)
        // UUID 1 en majuscules, UUID 2 en minuscules.
        assertEquals(segments[0], segments[0].uppercase())
        assertEquals(segments[1], segments[1].lowercase())
        assertEquals(36, segments[0].length)
        assertEquals("true", request.url.queryParameter("sync"))
        assertEquals("v3", request.url.queryParameter("shazamapiversion"))
        assertTrue(request.headers["User-Agent"]!!.startsWith("Dalvik/"))
        assertEquals("en_US", request.headers["Content-Language"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))

        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("Europe/Paris", body["timezone"]!!.jsonPrimitive.content)
        // Comme SongRec (`timestamp_ms as u32`) : millisecondes tronquées sur 32 bits.
        assertEquals((1_700_000_000_123L and 0xFFFFFFFFL).toString(), body["timestamp"]!!.jsonPrimitive.content)
        assertEquals("45", body["geolocation"]!!.jsonObject["latitude"]!!.jsonPrimitive.content)
        val signature = body["signature"]!!.jsonObject
        assertEquals("4000", signature["samplems"]!!.jsonPrimitive.content)
        // La signature envoyée est décodable et correspond à l'audio fourni.
        val decoded = DecodedSignature.decodeFromUri(signature["uri"]!!.jsonPrimitive.content)
        assertEquals(64_000, decoded.numberSamples)
        assertTrue(decoded.totalPeaks > 0)
    }

    @Test
    fun `aucune correspondance renvoie null`() = runTest {
        server.enqueue(json(NO_MATCH_JSON))
        assertNull(recognizer.recognize(audio, 16_000))
    }

    @Test
    fun `le silence ne declenche aucune requete`() = runTest {
        assertNull(recognizer.recognize(ShortArray(16_000 * 4), 16_000))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `HTTP 429 devient RecognitionUnavailable`() = runTest {
        server.enqueue(json("{}", code = 429))
        val e = runCatching { recognizer.recognize(audio, 16_000) }.exceptionOrNull()
        assertEquals(AppError.RecognitionUnavailable, (e as AppException).error)
    }

    @Test
    fun `HTTP 500 ou JSON invalide deviennent RecognitionUnavailable`() = runTest {
        server.enqueue(json("oops", code = 500))
        server.enqueue(json("<html>pas du json</html>"))
        repeat(2) {
            val e = runCatching { recognizer.recognize(audio, 16_000) }.exceptionOrNull()
            assertEquals(AppError.RecognitionUnavailable, (e as AppException).error)
        }
    }

    @Test
    fun `une erreur reseau devient Network`() = runTest {
        server.close() // connexion refusée
        val e = runCatching { recognizer.recognize(audio, 16_000) }.exceptionOrNull()
        assertEquals(AppError.Network, (e as AppException).error)
    }

    @Test
    fun `un taux d'echantillonnage autre que 16 kHz est refuse`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { recognizer.recognize(audio, 44_100) }
        }
    }

    @Test
    fun `parseResponse tolere les champs manquants`() {
        // Titre sans album ni pochette.
        val minimal = ShazamMusicRecognizer.parseResponse("""{"track":{"title":"A","subtitle":"B"}}""")
        assertEquals("A", minimal!!.title)
        assertNull(minimal.album)
        assertNull(minimal.artworkUrl)
        // `track` sans titre : pas de correspondance exploitable.
        assertNull(ShazamMusicRecognizer.parseResponse("""{"track":{"subtitle":"B"}}"""))
        assertNull(ShazamMusicRecognizer.parseResponse("""{"matches":[]}"""))
    }

    private companion object {
        val NO_MATCH_JSON = """{"matches":[],"timestamp":1700000000123,"timezone":"Europe/Paris","tagid":"abc"}"""

        val MATCH_JSON = """
        {
          "matches": [{"id":"1","offset":9.5,"timeskew":0.0,"frequencyskew":0.0}],
          "timestamp": 1700000000123,
          "timezone": "Europe/Paris",
          "tagid": "def",
          "track": {
            "layout": "5",
            "type": "music",
            "key": "123456",
            "title": "Papaoutai",
            "subtitle": "Stromae",
            "images": {
              "background": "https://is1-ssl.mzstatic.com/image/thumb/bg.jpg",
              "coverart": "https://is1-ssl.mzstatic.com/image/thumb/cover.jpg/400x400cc.jpg",
              "coverarthq": "https://is1-ssl.mzstatic.com/image/thumb/cover.jpg/800x800cc.jpg"
            },
            "genres": {"primary": "Dance"},
            "sections": [
              {"type": "SONG", "metapages": [], "tabname": "Song",
               "metadata": [
                 {"title": "Album", "text": "Racine carrée"},
                 {"title": "Label", "text": "Mosaert"},
                 {"title": "Released", "text": "2013"}
               ]},
              {"type": "VIDEO", "tabname": "Video"}
            ]
          }
        }
        """.trimIndent()
    }
}
