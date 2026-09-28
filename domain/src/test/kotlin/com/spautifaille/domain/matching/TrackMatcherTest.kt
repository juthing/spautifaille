package com.spautifaille.domain.matching

import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {

    private val matcher = TrackMatcher()

    // --- helpers -------------------------------------------------------------------------------

    private fun ms(mmss: String): Long {
        val (m, s) = mmss.split(':').map { it.toLong() }
        return (m * 60 + s) * 1000
    }

    private var nextId = 0
    private fun yt(title: String, artist: String, dur: String? = null, album: String? = null) =
        Track(id = "id%08d".format(nextId++), title = title, artist = artist, album = album, durationMs = dur?.let(::ms))

    private fun imp(title: String, artists: List<String>, dur: String? = null, album: String? = null) =
        ImportedTrack(title = title, artists = artists, album = album, durationMs = dur?.let(::ms))

    private fun ImportedTrack.vs(c: Track): Double = matcher.score(this, c)

    private fun assertMatched(i: ImportedTrack, c: Track) {
        val b = matcher.explain(i, c)
        val r = matcher.match(i, listOf(c))
        assertEquals("expected MATCHED for '${i.title}' vs '${c.title}' / '${c.artist}': $b", MatchStatus.MATCHED, r.status)
    }

    private fun assertNotMatched(i: ImportedTrack, c: Track) {
        val b = matcher.explain(i, c)
        val r = matcher.match(i, listOf(c))
        assertTrue("expected not MATCHED for '${i.title}' vs '${c.title}' / '${c.artist}': $b", r.status != MatchStatus.MATCHED)
    }

    // --- featuring -----------------------------------------------------------------------------

    @Test fun `with-featuring import matches plain candidate from the main artist`() {
        val i = imp("Stay (with Justin Bieber)", listOf("The Kid LAROI", "Justin Bieber"), "2:21", "F*CK LOVE 3: OVER YOU")
        assertMatched(i, yt("STAY", "The Kid LAROI", "2:22"))
    }

    @Test fun `featuring candidate title matches import with feat in the artist list only`() {
        val i = imp("Old Town Road", listOf("Lil Nas X", "Billy Ray Cyrus"), "1:53")
        assertMatched(i, yt("Old Town Road (feat. Billy Ray Cyrus)", "Lil Nas X", "1:53"))
    }

    @Test fun `remix import prefers remix candidate over original`() {
        val i = imp("Old Town Road - Remix", listOf("Lil Nas X", "Billy Ray Cyrus"), "2:37")
        val remix = yt("Old Town Road (feat. Billy Ray Cyrus) [Remix]", "Lil Nas X", "2:37")
        val original = yt("Old Town Road", "Lil Nas X", "1:53")
        assertMatched(i, remix)
        assertTrue(i.vs(remix) > i.vs(original) + 0.2)
        assertNotMatched(i, original)
        val result = matcher.match(i, listOf(original, remix))
        assertEquals(remix.id, result.best!!.track.id)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals(original.id, result.alternatives.firstOrNull()?.track?.id)
    }

    @Test fun `featured artist as channel of the candidate still matches`() {
        val i = imp("Señorita", listOf("Shawn Mendes", "Camila Cabello"), "3:10")
        assertMatched(i, yt("Señorita", "Camila Cabello", "3:11"))
    }

    // --- remasters, live ------------------------------------------------------------------------

    @Test fun `remaster suffix is ignored`() {
        val i = imp("Bohemian Rhapsody - Remastered 2011", listOf("Queen"), "5:55", "Greatest Hits I, II & III")
        assertMatched(i, yt("Bohemian Rhapsody", "Queen", "5:54"))
        assertMatched(i, yt("Bohemian Rhapsody (Remastered 2011)", "Queen", "5:55"))
        assertMatched(i, yt("Queen - Bohemian Rhapsody (Official Video Remastered)", "Queen Official", "5:55"))
    }

    @Test fun `live version does not win over studio version`() {
        val i = imp("Bohemian Rhapsody - Remastered 2011", listOf("Queen"), "5:55")
        val studio = yt("Bohemian Rhapsody", "Queen", "5:54")
        val live = yt("Bohemian Rhapsody (Live Aid 1985)", "Queen", "5:59")
        assertNotMatched(i, live)
        assertTrue(i.vs(live) < i.vs(studio) - 0.3)
        assertEquals(studio.id, matcher.match(i, listOf(live, studio)).best!!.track.id)
    }

    @Test fun `import with live tag rejects studio candidate softly`() {
        val i = imp("Wonderwall - Live", listOf("Oasis"), "4:18")
        val live = yt("Wonderwall (Live)", "Oasis", "4:19")
        val studio = yt("Wonderwall", "Oasis", "4:18")
        assertMatched(i, live)
        assertTrue(i.vs(live) > i.vs(studio))
        assertNotMatched(i, studio)
    }

    @Test fun `radio edit tag is a mild difference`() {
        val i = imp("Song 2 - Radio Edit", listOf("Blur"), "2:02")
        val edit = yt("Song 2 (Radio Edit)", "Blur", "2:02")
        assertMatched(i, edit)
    }

    // --- special characters ---------------------------------------------------------------------

    @Test fun `accents are neutral`() {
        assertMatched(imp("Déjà vu", listOf("Beyoncé", "JAY-Z"), "4:26"), yt("Deja Vu", "Beyonce", "4:27"))
        assertMatched(imp("Deja Vu", listOf("Beyonce"), "4:26"), yt("Déjà Vu", "Beyoncé", "4:26"))
        assertMatched(imp("Beyoncé", listOf("Beyoncé"), "3:00"), yt("Beyoncé", "Beyoncé", "3:00"))
    }

    @Test fun `slash and dash uploads match`() {
        val i = imp("Highway to Hell", listOf("AC/DC"), "3:28")
        assertMatched(i, yt("Highway to Hell", "AC/DC", "3:28"))
        assertMatched(i, yt("AC/DC — Highway to Hell", "Rock Classics", "3:29"))
        assertMatched(i, yt("Highway to Hell", "ACDC", "3:28"))
    }

    @Test fun `unicode punctuation in names`() {
        assertMatched(imp("Hoppípolla", listOf("Sigur Rós"), "4:28"), yt("Hoppípolla", "Sigur Rós", "4:29"))
        assertMatched(imp("Hoppípolla", listOf("Sigur Rós"), "4:28"), yt("Sigur Rós – Hoppípolla", "Sigur Rós", "4:29"))
        assertMatched(imp("Kickstart My Heart", listOf("Mötley Crüe"), "4:48"), yt("Kickstart My Heart", "Motley Crue", "4:48"))
    }

    @Test fun `japanese title is kept and matched`() {
        val i = imp("夜に駆ける", listOf("YOASOBI"), "4:21")
        val same = yt("夜に駆ける", "YOASOBI", "4:21")
        val other = yt("怪物", "YOASOBI", "3:14")
        assertMatched(i, same)
        assertNotMatched(i, other)
        assertEquals(same.id, matcher.match(i, listOf(other, same)).best!!.track.id)
    }

    @Test fun `japanese title with romanized decorations`() {
        val i = imp("夜に駆ける", listOf("YOASOBI"), "4:21")
        assertMatched(i, yt("YOASOBI - 夜に駆ける (Official Music Video)", "Ayase", "4:22"))
    }

    @Test fun `titles with emoji`() {
        assertMatched(imp("Stay", listOf("Rihanna"), "4:00"), yt("Stay 🔥🎶", "Rihanna", "4:00"))
        assertMatched(imp("Love Me Harder ❤", listOf("Ariana Grande"), "3:56"), yt("Love Me Harder", "Ariana Grande", "3:56"))
    }

    @Test fun `dollar and exclamation artists`() {
        assertMatched(imp("Paris", listOf("\$uicideboy\$"), "2:45"), yt("Paris", "\$uicideboy\$", "2:45"))
        assertMatched(imp("Paris", listOf("\$uicideboy\$"), "2:45"), yt("Paris", "Suicideboys", "2:45"))
        assertMatched(imp("So What", listOf("P!nk"), "3:35"), yt("So What", "P!NK", "3:35"))
        assertMatched(imp("So What", listOf("P!nk"), "3:35"), yt("So What", "Pink", "3:35"))
    }

    @Test fun `apostrophes are neutral`() {
        val i = imp("Don't Stop Me Now", listOf("Queen"), "3:29")
        assertMatched(i, yt("Dont Stop Me Now", "Queen", "3:29"))
        assertMatched(imp("Dont Stop Me Now", listOf("Queen"), "3:29"), yt("Don’t Stop Me Now", "Queen", "3:29"))
    }

    @Test fun `ampersand and and are equivalent`() {
        assertMatched(
            imp("The Sound of Silence", listOf("Simon & Garfunkel"), "3:05"),
            yt("The Sound of Silence", "Simon and Garfunkel", "3:05"),
        )
        assertMatched(
            imp("Rock & Roll", listOf("Led Zeppelin"), "3:40"),
            yt("Rock and Roll", "Led Zeppelin", "3:40"),
        )
    }

    // --- homonyms ------------------------------------------------------------------------------

    @Test fun `same title other artist is not matched`() {
        val i = imp("Hello", listOf("Adele"), "4:55")
        assertNotMatched(i, yt("Hello", "Lionel Richie", "4:22"))
        assertNotMatched(i, yt("Hello", "Lionel Richie", "4:55")) // même durée : jamais un match automatique
    }

    @Test fun `cover by random channel is not matched`() {
        val i = imp("Yesterday", listOf("The Beatles"), "2:05")
        val cover = yt("Yesterday (Cover)", "Sarah Smith Music", "2:05")
        assertNotMatched(i, cover)
        assertTrue(i.vs(cover) < 0.6)
        val coverWithArtist = yt("The Beatles - Yesterday (cover by Sarah)", "Sarah Smith Music", "2:05")
        assertNotMatched(i, coverWithArtist)
        assertMatched(i, yt("Yesterday", "The Beatles", "2:05"))
    }

    @Test fun `right pick among homonyms karaoke and live`() {
        val i = imp("Hello", listOf("Adele"), "4:55", "25")
        val candidates = listOf(
            yt("Hello", "Lionel Richie", "4:22"),
            yt("Hello (Karaoke Version)", "Sing King", "4:52"),
            yt("Hello (Live at the NRJ Music Awards)", "Adele", "4:24"),
            yt("Hello", "OMFG", "3:57"),
            yt("Hello", "Adele", "4:55", "25"),
            yt("Hello (Instrumental)", "Adele Type Beats", "4:55"),
            yt("Hello Goodbye", "The Beatles", "3:27"),
        )
        val r = matcher.match(i, candidates)
        assertEquals(MatchStatus.MATCHED, r.status)
        assertEquals(candidates[4].id, r.best!!.track.id)
        assertTrue(r.best.score >= 0.95)
        assertTrue(r.alternatives.size <= 3)
        assertTrue(r.alternatives.all { it.score < r.best.score })
        assertTrue(r.alternatives.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test fun `karaoke channel is penalized even without tag in title`() {
        val i = imp("Bad Guy", listOf("Billie Eilish"), "3:14")
        val karaoke = yt("Bad Guy", "Sing King Karaoke", "3:14")
        assertNotMatched(i, karaoke)
    }

    @Test fun `same artist but other song is not matched`() {
        val i = imp("Bad Guy", listOf("Billie Eilish"), "3:14")
        assertNotMatched(i, yt("Ocean Eyes", "Billie Eilish", "3:20"))
        assertNotMatched(i, yt("Happier Than Ever", "Billie Eilish", "3:15"))
    }

    @Test fun `title being a strict prefix of another title is not matched`() {
        val i = imp("Hello", listOf("Adele"), "4:55")
        assertNotMatched(i, yt("Hello Goodbye", "Adele", "4:55"))
    }

    // --- uploads -------------------------------------------------------------------------------

    @Test fun `artist - title upload by vevo channel`() {
        val i = imp("Blinding Lights", listOf("The Weeknd"), "3:20")
        assertMatched(i, yt("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO", "3:22"))
        assertMatched(i, yt("The Weeknd - Blinding Lights (Official Audio)", "The Weeknd", "3:22"))
    }

    @Test fun `artist - title upload by random channel`() {
        val i = imp("Blinding Lights", listOf("The Weeknd"), "3:20")
        assertMatched(i, yt("The Weeknd - Blinding Lights (Official Video)", "Pop Nation HD", "3:21"))
        assertMatched(i, yt("Blinding Lights - The Weeknd [Lyrics]", "Lyric Vault", "3:20"))
        assertMatched(i, yt("The Weeknd - Blinding Lights | Official Music Video 4K", "xX_music_Xx", "3:20"))
    }

    @Test fun `upload with unrelated artist prefix is not matched`() {
        val i = imp("Blinding Lights", listOf("The Weeknd"), "3:20")
        assertNotMatched(i, yt("Some Band - Blinding Lights (Official Video)", "Some Band", "3:20"))
    }

    // --- duration ------------------------------------------------------------------------------

    @Test fun `duration is penalized`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38")
        val ok = yt("Hurt", "Johnny Cash", "3:39")
        val short = yt("Hurt", "Johnny Cash", "2:30")
        val long = yt("Hurt", "Johnny Cash", "7:45")
        assertMatched(i, ok)
        assertNotMatched(i, short)
        assertNotMatched(i, long)
        assertTrue(i.vs(short) < i.vs(ok) - 0.2)
        assertTrue(i.vs(long) < i.vs(ok) - 0.2)
        assertTrue(i.vs(short) >= 0.6) // titre et artiste parfaits : à valider par l'utilisateur, pas rejeté
    }

    @Test fun `duration closeness curve`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:00")
        fun d(secs: Long) = matcher.explain(i, Track("x", "Hurt", "Johnny Cash", durationMs = ms("3:00") + secs * 1000)).duration!!
        assertEquals(1.0, d(0), 1e-9)
        assertEquals(1.0, d(3), 1e-9)
        assertEquals(1.0, d(-3), 1e-9)
        assertEquals(0.5, d(16), 0.02) // ~ milieu de [3 s, 30 s]
        assertEquals(0.0, d(30), 1e-9)
        assertEquals(0.0, d(300), 1e-9)
        assertTrue(d(10) > d(20))
    }

    @Test fun `missing durations are neutral but never enough for a perfect score`() {
        val noDur = imp("Hurt", listOf("Johnny Cash"))
        val withDur = imp("Hurt", listOf("Johnny Cash"), "3:38")
        assertMatched(noDur, yt("Hurt", "Johnny Cash", "3:38"))
        assertMatched(withDur, yt("Hurt", "Johnny Cash"))
        assertTrue(noDur.vs(yt("Hurt", "Johnny Cash", "3:38")) < withDur.vs(yt("Hurt", "Johnny Cash", "3:38")))
        assertEquals(null, matcher.explain(noDur, yt("Hurt", "Johnny Cash", "3:38")).duration)
        // Durée à 0 ou négative = inconnue.
        assertEquals(null, matcher.explain(withDur, Track("z", "Hurt", "Johnny Cash", durationMs = 0)).duration)
    }

    @Test fun `title only evidence needs review at best`() {
        val i = imp("Hurt", emptyList())
        val r = matcher.match(i, listOf(yt("Hurt", "Johnny Cash")))
        assertEquals(MatchStatus.NEEDS_REVIEW, r.status)
    }

    @Test fun `no artist on import still matches with title and duration`() {
        val i = imp("Hurt", emptyList(), "3:38")
        assertMatched(i, yt("Hurt", "Johnny Cash", "3:38"))
    }

    // --- album ---------------------------------------------------------------------------------

    @Test fun `album equality adds a small bonus`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38", "American IV: The Man Comes Around")
        val withAlbum = yt("Hurt", "Johnny Cash", "3:50", "American IV - The Man Comes Around")
        val withoutAlbum = yt("Hurt", "Johnny Cash", "3:50", "Greatest Hits")
        val bonus = matcher.explain(i, withAlbum).albumBonus
        assertTrue(bonus > 0.0 && bonus <= 0.05)
        assertEquals(0.0, matcher.explain(i, withoutAlbum).albumBonus, 0.0)
        assertTrue(i.vs(withAlbum) > i.vs(withoutAlbum))
    }

    @Test fun `scores stay within bounds`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38", "A")
        val best = matcher.score(i, yt("Hurt", "Johnny Cash", "3:38", "A"))
        assertTrue(best in 0.0..1.0)
        assertEquals(1.0, best, 1e-9)
        assertEquals(0.0, matcher.score(imp("", emptyList()), yt("Hurt", "Johnny Cash")), 1e-9)
    }

    // --- match() ---------------------------------------------------------------------------------

    @Test fun `match on empty candidates is not found`() {
        val r = matcher.match(imp("Hurt", listOf("Johnny Cash")), emptyList())
        assertEquals(MatchStatus.NOT_FOUND, r.status)
        assertNull(r.best)
        assertTrue(r.alternatives.isEmpty())
    }

    @Test fun `not found still returns the best candidate`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38")
        val r = matcher.match(i, listOf(yt("Completely Different Song", "Nobody", "5:00")))
        assertEquals(MatchStatus.NOT_FOUND, r.status)
        assertNotNull(r.best)
    }

    @Test fun `needs review between thresholds`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38")
        val r = matcher.match(i, listOf(yt("Hurt", "Johnny Cash", "2:30")))
        assertEquals(MatchStatus.NEEDS_REVIEW, r.status)
    }

    @Test fun `thresholds are configurable`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38")
        val c = yt("Hurt", "Johnny Cash", "2:30")
        val score = matcher.score(i, c)
        assertEquals(MatchStatus.MATCHED, TrackMatcher(autoThreshold = score - 0.01, reviewThreshold = 0.5).match(i, listOf(c)).status)
        assertEquals(MatchStatus.NOT_FOUND, TrackMatcher(autoThreshold = 0.99, reviewThreshold = score + 0.01).match(i, listOf(c)).status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inverted thresholds are rejected`() {
        TrackMatcher(autoThreshold = 0.5, reviewThreshold = 0.9)
    }

    @Test fun `alternatives are limited to three above 0_3 and exclude best`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38")
        val cands = (1..8).map { yt("Hurt", "Johnny Cash", "3:${38 + it}") } + yt("Zzz", "Nobody", "9:00")
        val r = matcher.match(i, cands)
        assertEquals(3, r.alternatives.size)
        assertTrue(r.alternatives.none { it.track.id == r.best!!.track.id })
        assertTrue(r.alternatives.all { it.score >= 0.3 })
        val poor = matcher.match(i, listOf(yt("Hurt", "Johnny Cash", "3:38"), yt("Zzz", "Nobody", "9:00")))
        assertTrue(poor.alternatives.isEmpty())
    }

    @Test fun `ties are broken by duration closeness then original order`() {
        // Sans durée importée : même score, la proximité de durée ne peut pas départager -> ordre d'origine.
        val i = imp("Hurt", listOf("Johnny Cash"))
        val a = yt("Hurt", "Johnny Cash", "3:38")
        val b = yt("Hurt", "Johnny Cash", "3:38")
        assertEquals(a.id, matcher.match(i, listOf(a, b)).best!!.track.id)
        assertEquals(b.id, matcher.match(i, listOf(b, a)).best!!.track.id)

        // Score plafonné à 1.0 pour les deux (bonus d'album vs durée exacte) : la durée la plus proche gagne.
        val target = imp("Hurt", listOf("Johnny Cash"), "3:38", "American IV")
        val albumButOff = yt("Hurt", "Johnny Cash", "3:44", "American IV")
        val exact = yt("Hurt", "Johnny Cash", "3:38", "Other")
        assertEquals(1.0, matcher.score(target, albumButOff), 1e-9)
        assertEquals(1.0, matcher.score(target, exact), 1e-9)
        assertEquals(exact.id, matcher.match(target, listOf(albumButOff, exact)).best!!.track.id)
    }

    @Test fun `duplicate candidate ids are scored once`() {
        val i = imp("Hurt", listOf("Johnny Cash"), "3:38")
        val c = yt("Hurt", "Johnny Cash", "3:38")
        val r = matcher.match(i, listOf(c, c, c))
        assertTrue(r.alternatives.isEmpty())
    }

    @Test fun `match ignores youtubeId`() {
        val i = ImportedTrack("Hurt", listOf("Johnny Cash"), durationMs = ms("3:38"), youtubeId = "abc")
        assertEquals(MatchStatus.MATCHED, matcher.match(i, listOf(yt("Hurt", "Johnny Cash", "3:38"))).status)
    }

    // --- buildQuery ----------------------------------------------------------------------------

    @Test fun `buildQuery joins primary artist and cleaned title`() {
        assertEquals("Queen Bohemian Rhapsody", matcher.buildQuery(imp("Bohemian Rhapsody - Remastered 2011", listOf("Queen"))))
        assertEquals("The Kid LAROI Stay", matcher.buildQuery(imp("Stay (with Justin Bieber)", listOf("The Kid LAROI", "Justin Bieber"))))
        assertEquals("Lil Nas X Old Town Road", matcher.buildQuery(imp("Old Town Road (feat. Billy Ray Cyrus)", listOf("Lil Nas X", "Billy Ray Cyrus"))))
        assertEquals("Adele Hello", matcher.buildQuery(imp("Hello (Official Video)", listOf("Adele"))))
    }

    @Test fun `buildQuery keeps version markers and drops brackets`() {
        assertEquals("Lil Nas X Old Town Road Remix", matcher.buildQuery(imp("Old Town Road - Remix", listOf("Lil Nas X"))))
        assertEquals("Oasis Wonderwall Live", matcher.buildQuery(imp("Wonderwall (Live)", listOf("Oasis"))))
        assertEquals("Blur Song 2 Radio Edit", matcher.buildQuery(imp("Song 2 - Radio Edit", listOf("Blur"))))
    }

    @Test fun `buildQuery keeps original characters`() {
        assertEquals("YOASOBI 夜に駆ける", matcher.buildQuery(imp("夜に駆ける", listOf("YOASOBI"))))
        assertEquals("AC/DC Highway to Hell", matcher.buildQuery(imp("Highway to Hell", listOf("AC/DC"))))
        assertEquals("Sigur Rós Hoppípolla", matcher.buildQuery(imp("Hoppípolla", listOf("Sigur Rós"))))
    }

    @Test fun `buildQuery without artist or with title that is only noise`() {
        assertEquals("Hurt", matcher.buildQuery(imp("Hurt", emptyList())))
        assertEquals("Queen Official Video", matcher.buildQuery(imp("(Official Video)", listOf("Queen"))))
    }
}
