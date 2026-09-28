package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.repository.TrackCache
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Tests réseau réels contre YouTube : `@Ignore` par défaut (retirer l'annotation pour les lancer). */
@Ignore("Live network test: run manually")
class LiveYoutubeTest {

    private val repo = NewPipeStreamRepository(
        NewPipeInitializer(OkHttpDownloader(OkHttpClient())),
        mockk<TrackCache>(relaxed = true),
        Dispatchers.IO,
    )

    @Test fun searchAndResolve() = runBlocking {
        val page = repo.search("daft punk around the world", SearchFilter.SONGS)
        assertTrue(page.items.isNotEmpty())
        val stream = repo.resolveAudio("dQw4w9WgXcQ", AudioQuality.BEST)
        println(stream)
        assertTrue(stream.url.startsWith("http") || stream.dashManifest != null)
    }
}
