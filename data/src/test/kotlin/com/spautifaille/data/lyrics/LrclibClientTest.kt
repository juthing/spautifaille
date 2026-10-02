package com.spautifaille.data.lyrics

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LrclibClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LrclibClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = LrclibClient(OkHttpClient(), Dispatchers.IO, server.url("/"), userAgent = "Test/1.0 (test)")
    }

    @After fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val trackJson = """
        {"id":1,"name":"Blinding Lights","trackName":"Blinding Lights","artistName":"The Weeknd",
         "albumName":"After Hours","duration":200.0,"instrumental":false,"hasWordSync":false,
         "plainLyrics":"Yeah\nI've been tryna call","syncedLyrics":"[00:13.42] Yeah\n[00:26.95] I've been tryna call",
         "lyricsfile":"version: '1.0'"}
    """.trimIndent()

    @Test fun getEnvoieLesParametresEtLeUserAgent() = runBlocking {
        server.enqueue(json(trackJson))
        val result = client.get("Blinding Lights", "The Weeknd", "After Hours", 200)!!

        val request = server.takeRequest()
        assertEquals("/api/get", request.url.encodedPath)
        assertEquals("Blinding Lights", request.url.queryParameter("track_name"))
        assertEquals("The Weeknd", request.url.queryParameter("artist_name"))
        assertEquals("After Hours", request.url.queryParameter("album_name"))
        assertEquals("200", request.url.queryParameter("duration"))
        assertEquals("Test/1.0 (test)", request.headers["User-Agent"])

        assertEquals("Blinding Lights", result.trackName)
        assertEquals("The Weeknd", result.artistName)
        assertEquals(200.0, result.durationSec!!, 0.0)
        assertTrue(result.syncedLyrics!!.startsWith("[00:13.42]"))
        assertEquals("Yeah\nI've been tryna call", result.plainLyrics)
    }

    @Test fun getOmetAlbumEtDureeAbsents() = runBlocking {
        server.enqueue(json(trackJson))
        client.get("t", "a", null, null)
        val url = server.takeRequest().url
        assertNull(url.queryParameter("album_name"))
        assertNull(url.queryParameter("duration"))
    }

    @Test fun get404DonneNull() = runBlocking {
        server.enqueue(json("""{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}""", 404))
        assertNull(client.get("t", "a", null, null))
    }

    @Test fun instrumentalEtChampsNullsAcceptes() = runBlocking {
        server.enqueue(
            json("""{"trackName":"Interlude","artistName":"X","duration":60,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""),
        )
        val c = client.get("Interlude", "X", null, 60)!!
        assertTrue(c.instrumental)
        assertNull(c.syncedLyrics)
        assertNull(c.plainLyrics)
        assertEquals(60.0, c.durationSec!!, 0.0)
    }

    @Test fun nomUtiliseSiTrackNameAbsent() = runBlocking {
        server.enqueue(json("""{"name":"Seul","artistName":"X"}"""))
        assertEquals("Seul", client.get("Seul", "X", null, null)!!.trackName)
    }

    @Test fun searchRenvoieLaListe() = runBlocking {
        server.enqueue(json("[$trackJson,$trackJson]"))
        val results = client.search(trackName = "Blinding Lights", artistName = "The Weeknd")
        assertEquals(2, results.size)
        val url = server.takeRequest().url
        assertEquals("/api/search", url.encodedPath)
        assertEquals("Blinding Lights", url.queryParameter("track_name"))
        assertNull(url.queryParameter("q"))
    }

    @Test fun searchTexteLibre() = runBlocking {
        server.enqueue(json("[]"))
        assertTrue(client.search(q = "the weeknd blinding lights").isEmpty())
        assertEquals("the weeknd blinding lights", server.takeRequest().url.queryParameter("q"))
    }

    @Test fun search404DonneListeVide() = runBlocking {
        server.enqueue(json("{}", 404))
        assertTrue(client.search(q = "x").isEmpty())
    }

    @Test fun erreurServeurEstUneErreurReseau() {
        server.enqueue(json("{}", 503))
        server.enqueue(json("{}", 429))
        for (i in 1..2) {
            val e = assertThrows(AppException::class.java) { runBlocking { client.get("t", "a", null, null) } }
            assertEquals(AppError.Network, e.error)
        }
    }

    @Test fun autreStatutEstInconnu() {
        server.enqueue(json("{}", 400))
        val e = assertThrows(AppException::class.java) { runBlocking { client.get("t", "a", null, null) } }
        assertTrue(e.error is AppError.Unknown)
    }

    @Test fun jsonIllisible() {
        server.enqueue(json("<html>pas du json</html>"))
        val e = assertThrows(AppException::class.java) { runBlocking { client.search(q = "x") } }
        assertTrue(e.error is AppError.Unknown)
    }

    @Test fun serveurInjoignableEstUneErreurReseau() {
        server.close()
        val e = assertThrows(AppException::class.java) { runBlocking { client.get("t", "a", null, null) } }
        assertEquals(AppError.Network, e.error)
    }
}
