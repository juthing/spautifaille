package com.spautifaille.data.download

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkedDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<Seen>()

    /** Ce que le serveur a reçu pour une requête. */
    private data class Seen(
        val method: String,
        val path: String,
        val range: String?,
        val rn: String?,
        val body: List<Byte>,
        val userAgent: String?,
        val rangeHeader: String?,
        val url: String,
    )

    private val data = ByteArray(10_000) { (it * 31 % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** Sert `data[range]` pour `/audio` ; [respond] permet de surcharger la réponse (403, 500...). */
    private fun serve(
        content: ByteArray = data,
        respond: (RecordedRequest, Int) -> MockResponse? = { _, _ -> null },
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.url.queryParameter("range")
                requests += Seen(
                    method = request.method,
                    path = request.url.encodedPath,
                    range = range,
                    rn = request.url.queryParameter("rn"),
                    body = request.body?.toByteArray()?.toList().orEmpty(),
                    userAgent = request.headers["User-Agent"],
                    rangeHeader = request.headers["Range"],
                    url = request.url.toString(),
                )
                respond(request, requests.size)?.let { return it }
                val (start, end) = range!!.split('-').map { it.toInt() }
                if (start >= content.size) return MockResponse.Builder().code(416).build()
                val slice = content.copyOfRange(start, minOf(end + 1, content.size))
                return MockResponse.Builder().code(206).body(Buffer().write(slice)).build()
            }
        }
        server.start()
    }

    private fun source(path: String = "/audio?itag=251&sig=abc%3D") =
        DownloadSource(server.url(path).toString(), mapOf("User-Agent" to "VisionOS-UA"))

    private suspend inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("${T::class.simpleName} attendue")
    }

    private fun downloader(chunk: Long = 4096) = ChunkedDownloader(OkHttpClient(), chunkSize = chunk)

    @Test
    fun downloadsInPostRangeChunksWithRequestCounter() = runTest {
        serve()
        val sink = tmp.newFile("a.part")
        val progress = mutableListOf<Pair<Long, Long?>>()

        val size = downloader().download(source(), sink, startOffset = 0, totalLength = data.size.toLong()) { d, t ->
            progress += d to t
        }

        assertEquals(data.size.toLong(), size)
        assertArrayEquals(data, sink.readBytes())
        assertEquals(listOf("0-4095", "4096-8191", "8192-9999"), requests.map { it.range })
        assertEquals(listOf("0", "1", "2"), requests.map { it.rn })
        requests.forEach {
            assertEquals("POST", it.method)
            assertEquals(listOf<Byte>(0x78, 0x00), it.body)
            assertEquals("VisionOS-UA", it.userAgent)
            // La plage passe dans l'URL, jamais dans l'en-tête Range.
            assertEquals(null, it.rangeHeader)
        }
        // Les paramètres d'origine (signature encodée) sont conservés.
        assertTrue(requests.all { it.url.contains("sig=abc%3D") })
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first < b.first })
        assertEquals(data.size.toLong() to data.size.toLong(), progress.last())
    }

    @Test
    fun resumesFromExistingPartialFile() = runTest {
        serve()
        val sink = tmp.newFile("b.part")
        // 5 000 octets valides + un reste corrompu par un arrêt brutal, qui doit être écrasé.
        sink.writeBytes(data.copyOfRange(0, 5000) + ByteArray(300) { 9 })

        val size = downloader().download(source(), sink, startOffset = 5000, totalLength = data.size.toLong())

        assertEquals(data.size.toLong(), size)
        assertArrayEquals(data, sink.readBytes())
        assertEquals("5000-9095", requests.first().range)
        assertEquals("0", requests.first().rn)
    }

    @Test
    fun alreadyCompleteFileMakesNoRequest() = runTest {
        serve()
        val sink = tmp.newFile("c.part").apply { writeBytes(data) }

        val size = downloader().download(source(), sink, startOffset = data.size.toLong(), totalLength = data.size.toLong())

        assertEquals(data.size.toLong(), size)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun forbiddenRefreshesTheSourceOnceAndContinuesFromCurrentOffset() = runTest {
        // L'ancienne URL (/old) marche pour le 1er bloc puis expire ; /audio (nouvelle URL) sert le reste.
        serve(respond = { req, n ->
            if (req.url.encodedPath == "/old" && n > 1) MockResponse.Builder().code(403).build() else null
        })
        var refreshed = 0
        val sink = tmp.newFile("d.part")

        val size = downloader().download(
            source = source("/old?itag=251"),
            sink = sink,
            startOffset = 0,
            totalLength = data.size.toLong(),
            onForbidden = {
                refreshed++
                source("/audio?itag=251&fresh=1")
            },
        )

        assertEquals(data.size.toLong(), size)
        assertArrayEquals(data, sink.readBytes())
        assertEquals(1, refreshed)
        assertEquals(listOf("/old", "/old", "/audio", "/audio"), requests.map { it.path })
        // Le bloc refusé est rejoué au même offset, avec un nouveau numéro de requête.
        assertEquals(listOf("0-4095", "4096-8191", "4096-8191", "8192-9999"), requests.map { it.range })
        assertEquals(listOf("0", "1", "2", "3"), requests.map { it.rn })
    }

    @Test
    fun secondConsecutiveForbiddenIsFatal() = runTest {
        serve(respond = { _, _ -> MockResponse.Builder().code(403).build() })
        var refreshed = 0

        val error = failure<HttpStatusException> {
                downloader().download(
                    source = source(),
                    sink = tmp.newFile("e.part"),
                    startOffset = 0,
                    totalLength = data.size.toLong(),
                    onForbidden = { refreshed++; source() },
                )
        }

        assertEquals(403, error.code)
        assertEquals(1, refreshed)
    }

    @Test
    fun forbiddenWithoutNewSourceThrows() = runTest {
        serve(respond = { _, _ -> MockResponse.Builder().code(403).build() })

        val error = failure<HttpStatusException> {
                downloader().download(source(), tmp.newFile("f.part"), startOffset = 0, totalLength = 100)
        }
        assertEquals(403, error.code)
    }

    @Test
    fun otherHttpErrorsAreRaised() = runTest {
        serve(respond = { _, _ -> MockResponse.Builder().code(500).build() })

        val error = failure<HttpStatusException> {
                downloader().download(source(), tmp.newFile("g.part"), startOffset = 0, totalLength = 100)
        }
        assertEquals(500, error.code)
    }

    @Test
    fun unknownTotalStopsOnShortChunk() = runTest {
        serve()
        val sink = tmp.newFile("h.part")

        val size = downloader().download(source(), sink, startOffset = 0, totalLength = null)

        assertEquals(data.size.toLong(), size)
        assertArrayEquals(data, sink.readBytes())
        assertEquals(3, requests.size)
    }

    @Test
    fun unknownTotalStopsOnOutOfRangeAfterAFullLastChunk() = runTest {
        val content = data.copyOfRange(0, 8192) // exactement 2 blocs
        serve(content)
        val sink = tmp.newFile("i.part")

        val size = downloader().download(source(), sink, startOffset = 0, totalLength = null)

        assertEquals(8192L, size)
        assertArrayEquals(content, sink.readBytes())
        assertEquals(3, requests.size) // le 3e bloc reçoit un 416 : fin de flux
    }

    @Test
    fun emptyResponseWithKnownTotalFails() = runTest {
        // Le serveur annonce 10 000 octets mais n'en sert que 4 096 : la 2e réponse est vide.
        serve(content = data.copyOfRange(0, 4096))

        val error = failure<IOException> {
                downloader().download(source(), tmp.newFile("j.part"), startOffset = 0, totalLength = data.size.toLong())
        }
        assertTrue(error is HttpStatusException || error.message.orEmpty().isNotEmpty())
    }

    @Test
    fun truncatesGarbageBeyondStartOffsetAndKeepsPrefix() = runTest {
        serve()
        val sink: File = tmp.newFile("k.part")
        sink.writeBytes(data.copyOfRange(0, 4096))

        downloader().download(source(), sink, startOffset = 4096, totalLength = data.size.toLong())

        assertArrayEquals(data, sink.readBytes())
        assertEquals("4096-8191", requests.first().range)
    }
}
