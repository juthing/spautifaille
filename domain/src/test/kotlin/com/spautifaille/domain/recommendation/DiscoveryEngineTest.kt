package com.spautifaille.domain.recommendation

import com.spautifaille.domain.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryEngineTest {

    private fun t(id: String, title: String = "Titre $id", artist: String = "Artiste $id", durationMs: Long? = 200_000L) =
        Track(id = id, title = title, artist = artist, durationMs = durationMs)

    private fun cands(vararg tracks: Track) = tracks.map { Candidate(it, "src") }

    private fun input(
        seeds: List<Track>,
        similar: Map<String, List<Track>>,
        recent: Set<String> = emptySet(),
        library: Set<String> = emptySet(),
        liked: Set<String> = emptySet(),
    ) = DiscoveryInput(
        seeds = seeds,
        similarBySeed = similar.mapValues { (_, v) -> v.map { Candidate(it, "src") } },
        recentlyPlayedIds = recent,
        libraryIds = library,
        likedIds = liked,
    )

    private fun ids(items: List<DiscoveryItem>) = items.map { it.track.id }

    private val engine = DiscoveryEngine()

    @Test
    fun `empty inputs give empty result`() {
        assertTrue(engine.build(input(emptyList(), emptyMap()), Random(1)).isEmpty())
        val seed = t("s")
        assertTrue(engine.build(input(listOf(seed), emptyMap()), Random(1)).isEmpty())
        assertTrue(engine.build(input(listOf(seed), mapOf("s" to emptyList())), Random(1)).isEmpty())
    }

    @Test
    fun `dedupes by video id across seeds`() {
        val s1 = t("s1")
        val s2 = t("s2")
        val shared = t("x", artist = "Commun")
        val out = engine.build(
            input(listOf(s1, s2), mapOf("s1" to listOf(shared, t("a")), "s2" to listOf(shared, t("b")))),
            Random(1),
        )
        assertEquals(setOf("x", "a", "b"), ids(out).toSet())
        assertEquals(3, out.size)
    }

    @Test
    fun `dedupes title variants of the same song`() {
        val seed = t("s")
        val variants = listOf(
            t("v1", "Blinding Lights (Official Video)", "The Weeknd"),
            t("v2", "Blinding Lights (Lyrics)", "The Weeknd"),
            t("v3", "Blinding Lights [Official Audio]", "TheWeekndVEVO"),
            t("v4", "The Weeknd - Blinding Lights (Official Music Video) HD", "The Weeknd"),
            t("v5", "Blinding Lights", "The Weeknd - Topic"),
        )
        val out = engine.build(input(listOf(seed), mapOf("s" to variants)), Random(1))
        assertEquals(listOf("v1"), ids(out))
    }

    @Test
    fun `keeps distinct versions such as live or remix`() {
        val seed = t("s")
        val out = engine.build(
            input(
                listOf(seed),
                mapOf(
                    "s" to listOf(
                        t("a", "Song", "Band"),
                        t("b", "Song (Live)", "Band"),
                        t("c", "Song (Remix)", "Band"),
                    ),
                ),
            ),
            Random(1),
        )
        assertEquals(setOf("a", "b", "c"), ids(out).toSet())
    }

    @Test
    fun `does not collapse same title by different artists`() {
        val out = engine.build(
            input(listOf(t("s")), mapOf("s" to listOf(t("a", "Home", "Artist A"), t("b", "Home", "Artist B")))),
            Random(1),
        )
        assertEquals(setOf("a", "b"), ids(out).toSet())
    }

    @Test
    fun `excludes seeds themselves and other uploads of a seed`() {
        val seed = t("s", "Song (Official Video)", "Band")
        val out = engine.build(
            input(
                listOf(seed),
                mapOf("s" to listOf(seed, t("dup", "Song (Lyrics)", "Band"), t("ok", "Other", "Someone"))),
            ),
            Random(1),
        )
        assertEquals(listOf("ok"), ids(out))
    }

    @Test
    fun `excludes recently played library and liked tracks`() {
        val out = engine.build(
            input(
                listOf(t("s")),
                mapOf("s" to listOf(t("r"), t("l"), t("k"), t("ok"))),
                recent = setOf("r"),
                library = setOf("l"),
                liked = setOf("k"),
            ),
            Random(1),
        )
        assertEquals(listOf("ok"), ids(out))
    }

    @Test
    fun `drops too short or too long tracks but keeps unknown durations`() {
        val out = engine.build(
            input(
                listOf(t("s")),
                mapOf(
                    "s" to listOf(
                        t("short", durationMs = 30_000),
                        t("long", durationMs = 3_600_000),
                        t("unknown", durationMs = null),
                        t("ok", durationMs = 240_000),
                    ),
                ),
            ),
            Random(1),
        )
        assertEquals(setOf("unknown", "ok"), ids(out).toSet())
    }

    @Test
    fun `duration filter can be disabled`() {
        val e = DiscoveryEngine(DiscoveryConfig(filterByDuration = false))
        val out = e.build(input(listOf(t("s")), mapOf("s" to listOf(t("short", durationMs = 5_000)))), Random(1))
        assertEquals(listOf("short"), ids(out))
    }

    @Test
    fun `at most maxPerArtist tracks per artist`() {
        val many = (1..8).map { t("a$it", "Chanson $it", "Star") } + t("b1", artist = "Autre")
        val out = engine.build(input(listOf(t("s")), mapOf("s" to many)), Random(3))
        assertEquals(3, out.count { it.track.artist == "Star" })
        assertEquals(4, out.size)
    }

    @Test
    fun `artist cap follows normalized artist including channel suffixes and featured artists`() {
        val tracks = listOf(
            t("a1", "Un", "Star"),
            t("a2", "Deux", "StarVEVO"),
            t("a3", "Trois", "Star - Topic"),
            t("a4", "Quatre", "Star & Guest"),
        )
        val out = engine.build(input(listOf(t("s")), mapOf("s" to listOf(*tracks.toTypedArray()))), Random(1))
        assertEquals(3, out.size)
    }

    @Test
    fun `no two consecutive tracks by the same artist when avoidable`() {
        repeat(30) { seed ->
            val tracks = listOf("A", "B", "C").flatMap { artist -> (1..3).map { t("$artist$it", "T $artist$it", artist) } }
            val out = engine.build(input(listOf(t("s")), mapOf("s" to tracks)), Random(seed))
            assertEquals(9, out.size)
            assertNoConsecutiveArtists(out)
        }
    }

    @Test
    fun `no consecutive artists on tight distributions`() {
        // 3 A + 2 B + 1 C : la seule issue est A ? A ? A avec B/C intercalés.
        repeat(30) { seed ->
            val tracks = (1..3).map { t("A$it", "TA$it", "A") } + (1..2).map { t("B$it", "TB$it", "B") } + t("C1", "TC1", "C")
            val out = engine.build(input(listOf(t("s")), mapOf("s" to tracks)), Random(seed))
            assertEquals(6, out.size)
            assertNoConsecutiveArtists(out)
        }
    }

    @Test
    fun `consecutive artists only when unavoidable`() {
        val tracks = (1..3).map { t("A$it", "TA$it", "A") } + t("B1", "TB1", "B")
        val out = engine.build(input(listOf(t("s")), mapOf("s" to tracks)), Random(1))
        assertEquals(4, out.size)
        assertEquals(3, out.count { it.track.artist == "A" })
    }

    @Test
    fun `round robin across seeds so one seed cannot dominate`() {
        val seeds = listOf(t("s1"), t("s2"), t("s3"))
        val similar = seeds.associate { s ->
            s.id to (1..20).map { t("${s.id}-$it", "Titre ${s.id}-$it", "Artiste ${s.id}-$it") }
        }
        val e = DiscoveryEngine(DiscoveryConfig(limit = 9))
        val out = e.build(input(seeds, similar), Random(5))
        assertEquals(9, out.size)
        seeds.forEach { s -> assertEquals(3, out.count { it.seedId == s.id }) }
        // Ce sont les 3 premiers de chaque seed qui sont retenus.
        assertEquals(
            seeds.flatMap { s -> (1..3).map { "${s.id}-$it" } }.toSet(),
            ids(out).toSet(),
        )
    }

    @Test
    fun `round robin skips duplicates and keeps balance`() {
        val seeds = listOf(t("s1"), t("s2"))
        val shared = (1..3).map { t("c$it", "Commun $it", "Artiste c$it") }
        val similar = mapOf(
            "s1" to shared + (1..5).map { t("a$it", "A $it", "Art a$it") },
            "s2" to shared + (1..5).map { t("b$it", "B $it", "Art b$it") },
        )
        val e = DiscoveryEngine(DiscoveryConfig(limit = 8))
        val out = e.build(input(seeds, similar), Random(2))
        assertEquals(8, out.size)
        assertEquals(8, ids(out).toSet().size)
        assertTrue(out.count { it.seedId == "s2" } >= 3)
    }

    @Test
    fun `respects limit`() {
        val many = (1..100).map { t("t$it", "Titre $it", "Artiste $it") }
        val out = DiscoveryEngine(DiscoveryConfig(limit = 20)).build(input(listOf(t("s")), mapOf("s" to many)), Random(1))
        assertEquals(20, out.size)
        assertEquals(50, engine.build(input(listOf(t("s")), mapOf("s" to many)), Random(1)).size)
    }

    @Test
    fun `deterministic for a given random seed and different across seeds`() {
        val many = (1..40).map { t("t$it", "Titre $it", "Artiste ${it % 15}") }
        val inp = input(listOf(t("s")), mapOf("s" to many))
        val a = ids(engine.build(inp, Random(42)))
        val b = ids(engine.build(inp, Random(42)))
        val c = ids(engine.build(inp, Random(43)))
        assertEquals(a, b)
        assertFalse(a == c)
        assertEquals(a.toSet(), c.toSet())
    }

    @Test
    fun `keeps seed and source of each item`() {
        val out = engine.build(
            DiscoveryInput(
                seeds = listOf(t("s")),
                similarBySeed = mapOf("s" to listOf(Candidate(t("a"), "youtube_related"))),
            ),
            Random(1),
        )
        assertEquals(DiscoveryItem(t("a"), "s", "youtube_related"), out.single())
    }

    @Test
    fun `blank artists are never grouped`() {
        val tracks = (1..5).map { t("t$it", "Titre $it", "") }
        val out = engine.build(input(listOf(t("s")), mapOf("s" to tracks)), Random(1))
        assertEquals(5, out.size)
    }

    private fun assertNoConsecutiveArtists(out: List<DiscoveryItem>) {
        out.zipWithNext().forEach { (x, y) ->
            assertFalse(
                "Deux titres consécutifs du même artiste : ${ids(out)}",
                DiscoveryEngine.artistKey(x.track) == DiscoveryEngine.artistKey(y.track),
            )
        }
    }
}
