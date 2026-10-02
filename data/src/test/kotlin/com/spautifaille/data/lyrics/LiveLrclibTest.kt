package com.spautifaille.data.lyrics

import com.spautifaille.domain.lyrics.Lyrics
import com.spautifaille.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests réseau réels contre LRCLIB. Désactivés par défaut ; pour les lancer :
 * `SPAUTIFAILLE_LIVE_TESTS=1 ./gradlew :data:testDebugUnitTest --tests "*LiveLrclibTest*"`
 */
class LiveLrclibTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var repo: LyricsRepositoryImpl

    @Before fun onlyWhenEnabled() {
        assumeTrue(System.getenv("SPAUTIFAILLE_LIVE_TESTS") == "1")
        repo = LyricsRepositoryImpl(
            LrclibClient(OkHttpClient(), Dispatchers.IO),
            FileLyricsCache(File(tmp.root, "lyrics")),
            Dispatchers.IO,
        )
    }

    @Test fun parolesSynchroniseesPourUnTitreConnu() = runBlocking {
        val track = Track(
            id = "4NRXx6U8ABQ",
            title = "The Weeknd - Blinding Lights (Official Video)",
            artist = "TheWeekndVEVO",
            durationMs = 202_000,
        )
        val lyrics = repo.lyrics(track)
        println("lyrics=${lyrics?.let { it::class.simpleName }}")
        assertTrue(lyrics is Lyrics.Synced || lyrics is Lyrics.Plain)
    }

    @Test fun titreInexistant() = runBlocking {
        assertNull(repo.lyrics(Track(id = "zzzzzzzzzzz", title = "qzxwvu jkhgfd poiuyt", artist = "mnbvcx lkjhgf")))
    }
}
