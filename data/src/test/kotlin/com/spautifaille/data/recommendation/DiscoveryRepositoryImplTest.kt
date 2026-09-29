package com.spautifaille.data.recommendation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.track
import com.spautifaille.data.repository.LibraryRepositoryImpl
import com.spautifaille.data.repository.PlaylistRepositoryImpl
import com.spautifaille.data.repository.TrackCacheImpl
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.recommendation.RecommendationSource
import com.spautifaille.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class DiscoveryRepositoryImplTest {

    private lateinit var db: SpautifailleDatabase
    private lateinit var library: LibraryRepositoryImpl
    private lateinit var repository: DiscoveryRepositoryImpl
    private val streams = mockk<StreamRepository>()
    private var now = 1_000_000_000L
    private var scheduled = 0

    @Before
    fun setUp() {
        db = createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        library = LibraryRepositoryImpl(
            db, db.trackDao(), db.playlistDao(), db.historyDao(), db.subscriptionDao(), { now },
        )
        // Par défaut : rien de similaire (les tests surchargent les titres de départ qui les intéressent).
        coEvery { streams.related(any()) } returns emptyList()
        coEvery { streams.mix(any()) } returns emptyList()
        repository = repository(YouTubeRelatedSource(streams))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(vararg sources: RecommendationSource) = DiscoveryRepositoryImpl(
        discoveryDao = db.discoveryDao(),
        library = library,
        playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), { now }),
        trackCache = TrackCacheImpl(db.trackDao()) { now },
        sources = sources.toSet(),
        scheduler = { scheduled++ },
        clock = { now },
        randomFactory = { Random(1) },
    )

    private fun similarTo(prefix: String, count: Int = 6) =
        (1..count).map { track("$prefix$it", title = "Titre $prefix$it", artist = "Artiste $prefix$it") }

    private suspend fun like(vararg ids: String) = ids.forEach { library.setLiked(track(it), true) }

    @Test
    fun `refresh stores ordered results and observe maps them to Discovery`() = runTest {
        like("seed1")
        coEvery { streams.related("seed1") } returns similarTo("a", 8)

        assertNull(repository.observe().first())
        repository.refresh()

        val discovery = repository.observe().first()
        assertNotNull(discovery)
        assertEquals(now, discovery!!.generatedAt)
        assertEquals((1..8).map { "a$it" }.toSet(), discovery.tracks.map { it.id }.toSet())
        assertEquals("Titre a1", discovery.tracks.first { it.id == "a1" }.title)

        val rows = db.discoveryDao().observe().first()
        assertEquals((0 until 8).toList(), rows.map { it.position })
        assertEquals(rows.map { it.trackId }, discovery.tracks.map { it.id })
        assertTrue(rows.all { it.seedTrackId == "seed1" && it.source == YouTubeRelatedSource.ID && it.generatedAt == now })
        // Les métadonnées des titres sont dans le cache `tracks`.
        assertEquals("Artiste a3", db.trackDao().get("a3")!!.artist)
    }

    @Test
    fun `refresh replaces the previous discovery`() = runTest {
        like("seed1")
        coEvery { streams.related("seed1") } returns similarTo("a", 4)
        repository.refresh()

        now += 1_000
        coEvery { streams.related("seed1") } returns similarTo("b", 3)
        repository.refresh()

        val discovery = repository.observe().first()!!
        assertEquals(setOf("b1", "b2", "b3"), discovery.tracks.map { it.id }.toSet())
        assertEquals(now, discovery.generatedAt)
    }

    @Test
    fun `excludes library, liked, seeds and recently played tracks`() = runTest {
        like("seed1", "seed2")
        library.recordPlay(track("heard"), playedAt = now - 60_000)
        library.recordPlay(track("old"), playedAt = now - 40L * 24 * 60 * 60 * 1000)
        val playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), { now })
        val pid = playlists.create("Ma playlist", listOf(track("inlib")))
        assertTrue(playlists.containsTrack(pid, "inlib"))

        val related = listOf(
            track("seed2"), track("heard"), track("inlib"), track("old"), track("fresh1"), track("fresh2"),
        ).mapIndexed { i, t -> t.copy(title = "Titre ${t.id}", artist = "Artiste $i") }
        coEvery { streams.related("seed1") } returns related

        repository.refresh()

        val ids = repository.observe().first()!!.tracks.map { it.id }.toSet()
        assertEquals(setOf("old", "fresh1", "fresh2"), ids)
    }

    @Test
    fun `failing seeds are skipped when others succeed`() = runTest {
        like("seed1", "seed2", "seed3")
        coEvery { streams.related("seed1") } throws AppException(AppError.Network)
        coEvery { streams.related("seed2") } returns similarTo("b")
        coEvery { streams.related("seed3") } throws RuntimeException("boom")
        coEvery { streams.mix(any()) } returns emptyList()

        repository.refresh()

        val discovery = repository.observe().first()!!
        assertEquals(6, discovery.tracks.size)
        assertTrue(discovery.tracks.all { it.id.startsWith("b") })
    }

    @Test
    fun `one failing source does not hide the other source`() = runTest {
        like("seed1")
        val broken = object : RecommendationSource {
            override val id = "broken"
            override suspend fun similar(seed: Track, limit: Int): List<Track> = throw AppException(AppError.BotDetected)
        }
        val good = object : RecommendationSource {
            override val id = "good"
            override suspend fun similar(seed: Track, limit: Int): List<Track> = similarTo("g", 3)
        }
        repository = repository(broken, good)

        repository.refresh()

        val rows = db.discoveryDao().observe().first()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.source == "good" })
    }

    @Test
    fun `all seeds failing throws the first AppException and keeps the cache`() = runTest {
        like("seed1", "seed2")
        coEvery { streams.related("seed1") } returns similarTo("a", 3)
        repository.refresh()
        val before = repository.observe().first()!!

        coEvery { streams.related("seed1") } throws AppException(AppError.Network)
        coEvery { streams.related("seed2") } throws AppException(AppError.BotDetected)
        coEvery { streams.mix(any()) } returns emptyList()

        try {
            repository.refresh()
            fail("AppException attendue")
        } catch (e: AppException) {
            // L'erreur du premier titre de départ (ordre de sélection) est relancée.
            assertTrue(e.error == AppError.Network || e.error == AppError.BotDetected)
        }
        assertEquals(before, repository.observe().first())
    }

    @Test
    fun `unexpected failures are wrapped in AppException`() = runTest {
        like("seed1")
        coEvery { streams.related("seed1") } throws IllegalStateException("kaboom")
        coEvery { streams.mix(any()) } returns emptyList()

        try {
            repository.refresh()
            fail("AppException attendue")
        } catch (e: AppException) {
            assertTrue(e.error is AppError.Unknown)
        }
    }

    @Test
    fun `no seeds clears the discovery without any network call`() = runTest {
        like("seed1")
        coEvery { streams.related("seed1") } returns similarTo("a", 3)
        repository.refresh()
        assertNotNull(repository.observe().first())

        library.setLiked(track("seed1"), false)
        repository.refresh()

        assertNull(repository.observe().first())
    }

    @Test
    fun `queries at most PARALLELISM seeds at a time`() = runTest {
        like(*(1..8).map { "seed$it" }.toTypedArray())
        var running = 0
        var maxRunning = 0
        val source = object : RecommendationSource {
            override val id = "slow"
            override suspend fun similar(seed: Track, limit: Int): List<Track> {
                running++
                maxRunning = maxOf(maxRunning, running)
                delay(100)
                running--
                return similarTo("r${seed.id}-", 2)
            }
        }
        repository = repository(source)

        repository.refresh()

        assertEquals(DiscoveryRepositoryImpl.PARALLELISM, maxRunning)
        assertEquals(16, repository.observe().first()!!.tracks.size)
    }

    @Test
    fun `observe emits null then content`() = runTest {
        like("seed1")
        coEvery { streams.related("seed1") } returns similarTo("a", 3)

        repository.observe().test {
            assertNull(awaitItem())
            repository.refresh()
            assertEquals(3, awaitItem()!!.tracks.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scheduleRefresh delegates to the scheduler and swallows its failures`() {
        repository.scheduleRefresh()
        assertEquals(1, scheduled)

        val failing = DiscoveryRepositoryImpl(
            discoveryDao = db.discoveryDao(),
            library = library,
            playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), { now }),
            trackCache = TrackCacheImpl(db.trackDao()) { now },
            sources = emptySet(),
            scheduler = { throw IllegalStateException("WorkManager not initialized") },
            clock = { now },
            randomFactory = { Random(1) },
        )
        failing.scheduleRefresh()
        assertFalse(scheduled > 1)
    }
}
