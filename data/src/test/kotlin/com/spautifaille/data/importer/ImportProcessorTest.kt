package com.spautifaille.data.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spautifaille.data.local.ImportJobEntity
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class ImportProcessorTest {

    private lateinit var env: ImportEnv

    @Before
    fun setUp() {
        env = ImportEnv()
    }

    @After
    fun tearDown() = env.close()

    private suspend fun job(id: Long): ImportJobEntity = checkNotNull(env.db.importDao().job(id))

    private fun perfect(id: String, title: String, artist: String = "Artiste") =
        Track(id = id, title = title, artist = artist, durationMs = 200_000L)

    @Test
    fun `items with a youtube id are matched directly without any search`() = runTest {
        val (jobId, playlistId) = env.newJob(
            listOf(
                imported("Un", youtubeId = "id1"),
                imported("Deux", youtubeId = "id2"),
                imported("Trois", youtubeId = "id3"),
            ),
        )
        env.trackCache.put(listOf(ytTrack("id1")))
        env.stream.trackHandler = { id -> ytTrack(id) }

        val outcome = env.processor().process(jobId)

        assertEquals(ImportOutcome.Done, outcome)
        assertTrue(env.stream.searches.isEmpty())
        assertEquals(listOf("id2", "id3"), env.stream.trackRequests) // id1 servi par le cache local
        assertEquals(listOf("id1", "id2", "id3"), env.playlistTrackIds(playlistId))
        val done = job(jobId)
        assertEquals(ImportJobState.COMPLETED.name, done.state)
        assertEquals(listOf(3, 3, 3, 0, 0), listOf(done.total, done.processed, done.matched, done.needsReview, done.notFound))
        val items = env.db.importDao().items(jobId)
        assertTrue(items.all { it.status == MatchStatus.MATCHED.name && it.bestScore == 1.0 && it.entryId != null })
    }

    @Test
    fun `unavailable youtube id is not found and other metadata errors fall back to a minimal track`() = runTest {
        val (jobId, playlistId) = env.newJob(
            listOf(
                imported("Supprimée", youtubeId = "gone"),
                imported("Bizarre", artist = "Groupe", youtubeId = "odd"),
            ),
        )
        env.stream.trackHandler = { id ->
            throw AppException(if (id == "gone") AppError.Unavailable else AppError.ExtractionBroken("x"))
        }

        env.processor().process(jobId)

        val items = env.db.importDao().items(jobId)
        assertEquals(MatchStatus.NOT_FOUND.name, items[0].status)
        assertEquals(MatchStatus.MATCHED.name, items[1].status)
        assertEquals(listOf("odd"), env.playlistTrackIds(playlistId))
        val stored = env.db.trackDao().get("odd")!!
        assertEquals("Bizarre", stored.title)
        assertEquals("Groupe", stored.artist)
    }

    @Test
    fun `search based matching produces the three statuses with counters and ordered playlist entries`() = runTest {
        val (jobId, playlistId) = env.newJob(
            listOf(
                imported("Blinding Lights", "The Weeknd"),
                imported("Inconnu Total", "Personne"),
                imported("Levitating", "Dua Lipa"),
                imported("Ma Chanson", "Alpha"),
            ),
        )
        env.stream.searchHandler = { query, _ ->
            when {
                "Blinding" in query -> listOf(
                    perfect("bl1", "Blinding Lights", "The Weeknd"),
                    Track("bl2", "Blinding Lights", "Cover Band", durationMs = 190_000L),
                )
                "Levitating" in query -> listOf(perfect("lv1", "Levitating", "Dua Lipa"))
                // Bon titre et bonne durée mais mauvais artiste : à vérifier.
                "Ma Chanson" in query -> listOf(perfect("mc1", "Ma Chanson", "Zzzz Qqqq"))
                else -> emptyList()
            }
        }

        val outcome = env.processor().process(jobId)

        assertEquals(ImportOutcome.Done, outcome)
        val items = env.db.importDao().items(jobId)
        assertEquals(
            listOf(MatchStatus.MATCHED, MatchStatus.NOT_FOUND, MatchStatus.MATCHED, MatchStatus.NEEDS_REVIEW).map { it.name },
            items.map { it.status },
        )
        // Playlist : MATCHED + NEEDS_REVIEW, dans l'ordre de la source ; NOT_FOUND absent.
        assertEquals(listOf("bl1", "lv1", "mc1"), env.playlistTrackIds(playlistId))
        assertEquals(listOf(0, 1, 2), env.playlistPositions(playlistId))
        assertNull(items[1].entryId)
        assertNotNull(items[3].entryId)
        val done = job(jobId)
        assertEquals(listOf(4, 4, 2, 1, 1), listOf(done.total, done.processed, done.matched, done.needsReview, done.notFound))
        assertEquals(ImportJobState.COMPLETED.name, done.state)

        // Candidats : meilleur en premier, métadonnées des alternatives conservées dans `tracks`.
        val candidates = decodeCandidates(items[0].candidates)
        assertEquals("bl1", candidates.first().trackId)
        assertEquals(items[0].bestTrackId, "bl1")
        assertTrue(candidates.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertNotNull(env.db.trackDao().get("bl2"))
    }

    @Test
    fun `not found on songs falls back to a video search and keeps the better result`() = runTest {
        val (jobId, playlistId) = env.newJob(listOf(imported("Rare Track", "Indie")))
        env.stream.searchHandler = { _, filter ->
            if (filter == SearchFilter.VIDEOS) listOf(perfect("v1", "Rare Track", "Indie")) else emptyList()
        }

        env.processor().process(jobId)

        assertEquals(listOf(SearchFilter.SONGS, SearchFilter.VIDEOS), env.stream.searches.map { it.second })
        assertEquals(listOf("v1"), env.playlistTrackIds(playlistId))
        assertEquals(MatchStatus.MATCHED.name, env.db.importDao().items(jobId).single().status)
    }

    @Test
    fun `resumption only processes pending items`() = runTest {
        val (jobId, playlistId) = env.newJob(listOf(imported("Un"), imported("Deux"), imported("Trois")))
        var failFromThird = true
        env.stream.searchHandler = { query, _ ->
            if ("Trois" in query && failFromThird) throw AppException(AppError.Network)
            listOf(perfect("t-" + query.substringAfter(' '), query.substringAfter(' ')))
        }

        val first = env.processor().process(jobId)

        assertEquals(ImportOutcome.Retry(AppError.Network), first)
        assertEquals(RUNNING, job(jobId).state)
        assertEquals(2, job(jobId).processed)
        assertEquals(listOf("t-Un", "t-Deux"), env.playlistTrackIds(playlistId))

        failFromThird = false
        env.stream.searches.clear()
        val second = env.processor().process(jobId)

        assertEquals(ImportOutcome.Done, second)
        assertEquals(listOf("Artiste Trois"), env.stream.searches.map { it.first })
        assertEquals(listOf("t-Un", "t-Deux", "t-Trois"), env.playlistTrackIds(playlistId))
        assertEquals(ImportJobState.COMPLETED.name, job(jobId).state)
        assertEquals(3, job(jobId).matched)
    }

    @Test
    fun `bot detection waits with growing backoff and then succeeds`() = runTest {
        val (jobId, _) = env.newJob(listOf(imported("Un")))
        var calls = 0
        env.stream.searchHandler = { _, _ ->
            if (++calls <= 2) throw AppException(AppError.BotDetected)
            listOf(perfect("t1", "Un"))
        }

        val outcome = env.processor(throttleMs = 0).process(jobId)

        assertEquals(ImportOutcome.Done, outcome)
        assertEquals(30_000L + 60_000L, testScheduler.currentTime)
        assertEquals(MatchStatus.MATCHED.name, env.db.importDao().items(jobId).single().status)
    }

    @Test
    fun `bot detection gives up after three waits and asks for a retry`() = runTest {
        val (jobId, _) = env.newJob(listOf(imported("Un"), imported("Deux")))
        env.stream.searchHandler = { _, _ -> throw AppException(AppError.BotDetected) }

        val outcome = env.processor(throttleMs = 0).process(jobId)

        assertEquals(ImportOutcome.Retry(AppError.BotDetected), outcome)
        assertEquals(30_000L + 60_000L + 120_000L, testScheduler.currentTime)
        assertEquals(0, job(jobId).processed)
        assertEquals(RUNNING, job(jobId).state)
    }

    @Test
    fun `unexpected error on one item marks it not found and continues`() = runTest {
        val (jobId, playlistId) = env.newJob(listOf(imported("Un"), imported("Boom"), imported("Trois")))
        env.stream.searchHandler = { query, _ ->
            if ("Boom" in query) throw IllegalStateException("kaboom")
            listOf(perfect("t-" + query.substringAfter(' '), query.substringAfter(' ')))
        }

        val outcome = env.processor().process(jobId)

        assertEquals(ImportOutcome.Done, outcome)
        assertEquals(
            listOf(MatchStatus.MATCHED, MatchStatus.NOT_FOUND, MatchStatus.MATCHED).map { it.name },
            env.db.importDao().items(jobId).map { it.status },
        )
        assertEquals(listOf("t-Un", "t-Trois"), env.playlistTrackIds(playlistId))
        assertEquals(1, job(jobId).notFound)
    }

    @Test
    fun `searches are throttled`() = runTest {
        val (jobId, _) = env.newJob(listOf(imported("Un"), imported("Deux"), imported("Trois")))
        env.stream.searchHandler = { query, _ -> listOf(perfect("t-" + query.substringAfter(' '), query.substringAfter(' '))) }

        env.processor(throttleMs = 300).process(jobId)

        assertEquals(3 * 300L, testScheduler.currentTime)
    }

    @Test
    fun `progress callback is invoked after each item`() = runTest {
        val (jobId, _) = env.newJob(listOf(imported("Un", youtubeId = "a"), imported("Deux", youtubeId = "b")))
        env.stream.trackHandler = { ytTrack(it) }
        val seen = mutableListOf<Int>()

        env.processor().process(jobId) { seen += it.processed }

        assertEquals(listOf(1, 2, 2), seen) // le dernier appel = passage en COMPLETED
    }

    @Test
    fun `job deleted while running stops processing without adding entries`() = runTest {
        val (jobId, playlistId) = env.newJob(listOf(imported("Un", youtubeId = "a"), imported("Deux", youtubeId = "b")))
        env.stream.trackHandler = { ytTrack(it) }

        val outcome = env.processor().process(jobId) { env.db.importDao().deleteJob(jobId) }

        assertEquals(ImportOutcome.Done, outcome)
        assertEquals(listOf("a"), env.playlistTrackIds(playlistId))
        assertNull(env.db.importDao().job(jobId))
    }

    private companion object {
        val RUNNING = ImportJobState.RUNNING.name
    }
}
