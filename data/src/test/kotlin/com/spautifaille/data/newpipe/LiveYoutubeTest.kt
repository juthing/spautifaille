package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.repository.TrackCache
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests réseau réels contre YouTube. Désactivés par défaut ; pour les lancer :
 * `SPAUTIFAILLE_LIVE_TESTS=1 ./gradlew :data:testDebugUnitTest --tests "*LiveYoutubeTest*"`
 */
class LiveYoutubeTest {

    private val client = OkHttpClient()
    private val loudness = FakeLoudnessStore()
    private val repo = NewPipeStreamRepository(
        NewPipeInitializer(OkHttpDownloader(client, PlayerLoudnessRecorder(loudness))),
        mockk<TrackCache>(relaxed = true),
        Dispatchers.IO,
        loudness,
    )

    @Before fun onlyWhenEnabled() {
        assumeTrue(System.getenv("SPAUTIFAILLE_LIVE_TESTS") == "1")
    }

    @Test fun searchSuggestionsAndRelated() = runBlocking {
        val suggestions = repo.suggestions("daft pu")
        println("suggestions=$suggestions")
        assertTrue(suggestions.isNotEmpty())

        val page = repo.search("daft punk around the world", SearchFilter.SONGS)
        val tracks = page.items.filterIsInstance<SearchResult.TrackResult>().map { it.track }
        println("songs=${tracks.take(3)}")
        assertTrue(tracks.isNotEmpty())

        for (filter in SearchFilter.entries) {
            val results = repo.search("daft punk", filter)
            println("filter=$filter count=${results.items.size} first=${results.items.firstOrNull()} hasMore=${results.hasMore}")
            assertTrue("no results for $filter", results.items.isNotEmpty())
        }
        val next = repo.search("daft punk", SearchFilter.SONGS).next
        if (next != null) {
            val page2 = repo.search("daft punk", SearchFilter.SONGS, next)
            println("songs page2=${page2.items.size}")
            assertTrue(page2.items.isNotEmpty())
        }

        val related = repo.related(tracks.first().id)
        println("related=${related.size}")
        assertTrue(related.isNotEmpty())
    }

    /** Le niveau sonore de la réponse `player` VisionOS est relevé par le Downloader puis porté par le flux résolu. */
    @Test fun resolvedStreamCarriesLoudness() = runBlocking {
        val stream = repo.resolveAudio("JGwWNGJdvx8", AudioQuality.BEST)
        println("loudnessDb=${stream.loudnessDb}")
        val db = stream.loudnessDb
        assertTrue("loudnessDb absent de la réponse player", db != null)
        assertTrue("valeur implausible : $db", db!! in -30f..30f)
        assertEquals(db, loudness.values["JGwWNGJdvx8"])
    }

    /** Vérifie que l'URL résolue est réellement lisible avec la stratégie du lecteur (POST + &range=). */
    @Test fun resolvedStreamIsReadable() = runBlocking {
        val stream = repo.resolveAudio("dQw4w9WgXcQ", AudioQuality.BEST)
        println("stream=${stream.copy(url = stream.url.take(80))}")
        assertTrue(stream.url.startsWith("http"))

        val url = stream.url + "&range=0-65535&rn=1"
        val request = Request.Builder().url(url)
            .apply { stream.headers.forEach { (k, v) -> header(k, v) } }
            .post(byteArrayOf(0x78, 0x00).toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            println("HTTP ${response.code} ${response.header("Content-Type")} len=${response.body.contentLength()}")
            assertEquals(200, response.code)
            assertTrue(response.body.bytes().size > 1000)
        }
    }

    @Test fun remotePlaylistAndMix() = runBlocking {
        val playlist = repo.remotePlaylist("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI")
        println("playlist=${playlist.playlist} tracks=${playlist.tracks.items.size}")
        assertTrue(playlist.tracks.items.isNotEmpty())
        val mix = repo.mix("dQw4w9WgXcQ")
        println("mix=${mix.size}")
    }
}
