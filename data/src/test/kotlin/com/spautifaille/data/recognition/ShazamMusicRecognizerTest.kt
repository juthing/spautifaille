package com.spautifaille.data.recognition

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
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

    @Test
    fun `parseResponse lit une vraie reponse Shazam avec correspondance`() {
        // Réponse réelle (voir resources/recognition/README.txt).
        val text = javaClass.getResourceAsStream("/recognition/shazam_match_local_forecast.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val track = ShazamMusicRecognizer.parseResponse(text)!!
        assertEquals("Local Forecast", track.title)
        assertEquals("Kevin MacLeod", track.artist)
        assertEquals("Groovy", track.album)
        assertEquals("2014", track.releaseYear)
        assertEquals("Electronic", track.genre)
        assertTrue(track.artworkUrl!!.startsWith("https://is1-ssl.mzstatic.com/"))
    }

    /**
     * Régression : le corps de la réponse (lecture réseau bloquante) était lu sur le thread de l'appelant, c'est-à-dire
     * le thread principal puisque le use case est collecté dans `viewModelScope`. Sur Android cela lève
     * `NetworkOnMainThreadException` (non `AppException` -> « Une erreur inattendue s'est produite ») dès que le corps
     * n'est pas déjà entièrement en tampon, ce qui arrive avec la réponse volumineuse d'une correspondance.
     */
    @Test
    fun `le corps de la reponse n'est jamais lu sur le thread de l'appelant`() {
        val readThreads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val tracingClient = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val traced = object : okio.ForwardingSource(body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    readThreads += Thread.currentThread().name
                    return super.read(sink, byteCount)
                }
            }
            response.newBuilder()
                .body(traced.buffer().asResponseBody(body.contentType(), body.contentLength()))
                .build()
        }.build()
        val tracing = ShazamMusicRecognizer(
            client = tracingClient,
            defaultDispatcher = Dispatchers.Default,
            endpoint = server.url("/tag").toString().trimEnd('/'),
        )
        server.enqueue(
            MockResponse.Builder().code(200).body(MATCH_JSON)
                .throttleBody(64, 5, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )

        val callerThread = "fake-main"
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, callerThread) }
        try {
            val track = kotlinx.coroutines.runBlocking(executor.asCoroutineDispatcher()) {
                tracing.recognize(audio, 16_000)
            }
            assertEquals("Papaoutai", track!!.title)
        } finally {
            executor.shutdown()
        }
        assertTrue("Le corps n'a jamais été lu", readThreads.isNotEmpty())
        // Les noms de threads portent un suffixe « @coroutine#n » en mode debug des coroutines.
        assertTrue("Corps lu sur le thread de l'appelant : $readThreads", readThreads.none { it.startsWith(callerThread) })
    }

    @Test
    fun `une coupure pendant la lecture du corps devient Network`() = runTest {
        val cutting = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val failing = object : okio.ForwardingSource(body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = throw java.io.IOException("coupure")
            }
            response.newBuilder().body(failing.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
        }.build()
        val cut = ShazamMusicRecognizer(
            client = cutting,
            defaultDispatcher = Dispatchers.Default,
            endpoint = server.url("/tag").toString().trimEnd('/'),
        )
        server.enqueue(json(MATCH_JSON))
        val e = runCatching { cut.recognize(audio, 16_000) }.exceptionOrNull()
        assertEquals(AppError.Network, (e as AppException).error)
    }

    @Test
    fun `une exception inattendue de la chaine devient RecognitionUnavailable avec sa cause`() = runTest {
        val boom = IllegalStateException("inattendu")
        val failing = ShazamMusicRecognizer(
            client = OkHttpClient(),
            defaultDispatcher = Dispatchers.Default,
            endpoint = server.url("/tag").toString().trimEnd('/'),
            clock = { throw boom },
        )
        val e = runCatching { failing.recognize(audio, 16_000) }.exceptionOrNull()
        assertEquals(AppError.RecognitionUnavailable, (e as AppException).error)
        assertEquals(boom, e.cause)
    }

    @Test
    fun `annuler la coroutine reste une annulation`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(NO_MATCH_JSON).bodyDelay(5, java.util.concurrent.TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.Default) { recognizer.recognize(audio, 16_000) }
        delay(300)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
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
