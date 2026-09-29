package com.spautifaille.domain.recommendation

import com.spautifaille.domain.model.PlayCount
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.PlaylistWithTracks
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedSelectorTest {

    private val now = 100L * 24 * 60 * 60 * 1000
    private val library = mockk<LibraryRepository>()
    private val playlists = mockk<PlaylistRepository>()

    private fun t(id: String) = Track(id = id, title = "Titre $id", artist = "Artiste $id")

    private fun playlist(id: Long, tracks: List<Track>) = Playlist(id, "P$id", tracks.size, null, false, 0, 0) to
        PlaylistWithTracks(
            Playlist(id, "P$id", tracks.size, null, false, 0, 0),
            tracks.mapIndexed { i, tr -> PlaylistEntry(id * 100 + i, i, tr) },
        )

    private fun givenLibrary(liked: List<Track> = emptyList(), top: List<Track> = emptyList()) {
        coEvery { library.recentlyLiked(any()) } returns liked
        coEvery { library.mostPlayed(any(), any()) } returns top.map { PlayCount(it, 3, 0) }
    }

    private fun givenPlaylists(vararg entries: Pair<Playlist, PlaylistWithTracks>) {
        every { playlists.observePlaylists() } returns flowOf(entries.map { it.first })
        entries.forEach { (p, full) -> every { playlists.observePlaylist(p.id) } returns flowOf(full) }
    }

    private fun selector(config: SeedConfig = SeedConfig()) = SeedSelector(library, playlists, config) { now }

    @Test
    fun `new user gives no seeds`() = runTest {
        givenLibrary()
        givenPlaylists()
        assertTrue(selector().select(Random(1)).isEmpty())
    }

    @Test
    fun `combines liked then top played then playlist picks`() = runTest {
        givenLibrary(liked = listOf(t("l1"), t("l2")), top = listOf(t("p1"), t("p2")))
        givenPlaylists(playlist(2, listOf(t("x1"), t("x2"), t("x3"), t("x4"))))

        val seeds = selector().select(Random(1)).map { it.id }

        assertEquals(listOf("l1", "l2", "p1", "p2"), seeds.take(4))
        assertEquals(3, seeds.drop(4).size)
        assertTrue(seeds.drop(4).all { it.startsWith("x") })
    }

    @Test
    fun `dedupes across the three sources`() = runTest {
        givenLibrary(liked = listOf(t("a"), t("b")), top = listOf(t("b"), t("c")))
        givenPlaylists(playlist(2, listOf(t("a"), t("c"), t("d"))))

        val seeds = selector().select(Random(1)).map { it.id }

        assertEquals(seeds.size, seeds.toSet().size)
        assertEquals(setOf("a", "b", "c", "d"), seeds.toSet())
    }

    @Test
    fun `top played window and counts follow the config`() = runTest {
        givenLibrary(top = (1..10).map { t("p$it") })
        givenPlaylists()

        val seeds = selector(SeedConfig(likedCount = 2, topPlayedCount = 4, topPlayedWindowDays = 30)).select(Random(1))

        assertEquals(listOf("p1", "p2", "p3", "p4"), seeds.map { it.id })
        coVerify { library.recentlyLiked(2) }
        coVerify { library.mostPlayed(any(), now - 30L * 24 * 60 * 60 * 1000) }
    }

    @Test
    fun `random playlist picks are deterministic for a given random seed`() = runTest {
        givenLibrary()
        givenPlaylists(
            playlist(2, (1..10).map { t("a$it") }),
            playlist(3, (1..10).map { t("b$it") }),
        )

        val first = selector().select(Random(7)).map { it.id }
        val second = selector().select(Random(7)).map { it.id }

        assertEquals(3, first.size)
        assertEquals(first, second)
    }

    @Test
    fun `empty playlists are ignored`() = runTest {
        givenLibrary()
        val empty = playlist(2, emptyList())
        givenPlaylists(empty, playlist(3, listOf(t("z"))))

        assertEquals(listOf("z"), selector().select(Random(1)).map { it.id })
    }
}
