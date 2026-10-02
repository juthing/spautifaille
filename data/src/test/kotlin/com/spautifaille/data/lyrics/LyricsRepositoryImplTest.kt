package com.spautifaille.data.lyrics

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.lyrics.Lyrics
import com.spautifaille.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LyricsRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: LyricsRepositoryImpl
    private val cache = MemoryCache()
    private val requests = mutableListOf<String>()

    /** Réponses par chemin ; 404 par défaut, `[]` pour la recherche. */
    private var getResponse: (RecordedRequest) -> MockResponse = { notFound() }
    private var searchResponse: (RecordedRequest) -> MockResponse = { json("[]") }

    private class MemoryCache : LyricsCache {
        val store = mutableMapOf<String, Lyrics?>()
        override fun read(videoId: String) = if (videoId in store) CachedLyrics(store[videoId]) else null
        override fun write(videoId: String, lyrics: Lyrics?) { store[videoId] = lyrics }
    }

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.url.encodedPath + "?" + request.url.encodedQuery
                    return when (request.url.encodedPath) {
                        "/api/get" -> getResponse(request)
                        "/api/search" -> searchResponse(request)
                        else -> notFound()
                    }
                }
            }
            start()
        }
        repo = LyricsRepositoryImpl(LrclibClient(OkHttpClient(), Dispatchers.IO, server.url("/")), cache, Dispatchers.IO)
    }

    @After fun tearDown() {
        server.close()
    }

    private fun notFound() = json("""{"statusCode":404}""", 404)
    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun entry(
        title: String = "Blinding Lights",
        artist: String = "The Weeknd",
        duration: Int = 200,
        synced: String? = "[00:10.00]Yeah\n[00:20.00]Hey",
        plain: String? = "Yeah\nHey",
        instrumental: Boolean = false,
    ) = buildString {
        append("""{"trackName":${q(title)},"artistName":${q(artist)},"duration":$duration,"instrumental":$instrumental,""")
        append(""""plainLyrics":${plain?.let(::q)},"syncedLyrics":${synced?.let(::q)}}""")
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private val track = Track(
        id = "vid1",
        title = "Blinding Lights (Official Video)",
        artist = "The Weeknd - Topic",
        durationMs = 200_000,
    )

    @Test fun correspondanceExacteEtNettoyageDeLaRequete() = runBlocking {
        getResponse = { json(entry()) }
        val result = repo.lyrics(track)
        assertTrue(result is Lyrics.Synced)
        assertEquals(1, requests.size)
        assertTrue(requests.single(), requests.single().contains("track_name=Blinding%20Lights&artist_name=The%20Weeknd"))
        assertTrue(requests.single().contains("duration=200"))
    }

    @Test fun repliSurLaRechercheEtChoixDuMeilleurCandidat() = runBlocking {
        searchResponse = {
            json(
                "[" + listOf(
                    entry(duration = 230, synced = "[00:01.00]loin"),
                    entry(duration = 201, synced = "[00:01.00]proche"),
                    entry(title = "Autre chose", synced = "[00:01.00]hors sujet"),
                ).joinToString(",") + "]",
            )
        }
        val result = repo.lyrics(track) as Lyrics.Synced
        assertEquals("proche", result.lines.single().text)
        assertTrue(requests.any { it.startsWith("/api/get") })
        assertTrue(requests.any { it.startsWith("/api/search?track_name=") })
    }

    @Test fun repliSurLaRechercheLibre() = runBlocking {
        searchResponse = { request ->
            if (request.url.queryParameter("q") != null) json("[${entry()}]") else json("[]")
        }
        assertTrue(repo.lyrics(track) is Lyrics.Synced)
        assertTrue(requests.last().startsWith("/api/search?q="))
    }

    @Test fun instrumentalEstRenvoye() = runBlocking {
        getResponse = { json(entry(synced = null, plain = null, instrumental = true)) }
        assertEquals(Lyrics.Instrumental(), repo.lyrics(track))
    }

    @Test fun parolesBrutesSeulement() = runBlocking {
        getResponse = { json(entry(synced = null)) }
        assertEquals(Lyrics.Plain("Yeah\nHey"), repo.lyrics(track))
    }

    @Test fun introuvableEstMisEnCacheEtNeRetapePasLeReseau() = runBlocking {
        assertNull(repo.lyrics(track))
        assertTrue(cache.store.containsKey("vid1"))
        val after = requests.size
        assertNull(repo.lyrics(track))
        assertEquals(after, requests.size)
    }

    @Test fun trouveEstServiDepuisLeCache() = runBlocking {
        getResponse = { json(entry()) }
        val first = repo.lyrics(track)
        val calls = requests.size
        assertEquals(first, repo.lyrics(track))
        assertEquals(calls, requests.size)
    }

    @Test fun erreurReseauNonMiseEnCache() {
        getResponse = { json("{}", 503) }
        val e = assertThrows(AppException::class.java) { runBlocking { repo.lyrics(track) } }
        assertEquals(AppError.Network, e.error)
        assertTrue(cache.store.isEmpty())
    }

    @Test fun artistePrincipalEssayeSiLArtisteCompletEchoue() = runBlocking {
        getResponse = { request ->
            if (request.url.queryParameter("artist_name") == "Calvin Harris") json(entry(artist = "Calvin Harris")) else notFound()
        }
        val multi = Track(id = "v2", title = "Blinding Lights", artist = "Calvin Harris, Dua Lipa", durationMs = 200_000)
        assertTrue(repo.lyrics(multi) is Lyrics.Synced)
    }
}
