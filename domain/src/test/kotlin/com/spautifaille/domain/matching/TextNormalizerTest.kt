package com.spautifaille.domain.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    // --- normalize -----------------------------------------------------------------------------

    @Test fun `normalize lowercases and strips latin diacritics`() {
        assertEquals("deja vu", TextNormalizer.normalize("Déjà vu"))
        assertEquals("beyonce", TextNormalizer.normalize("Beyoncé"))
        assertEquals("sigur ros hoppipolla", TextNormalizer.normalize("Sigur Rós – Hoppípolla"))
        assertEquals("motley crue", TextNormalizer.normalize("Mötley Crüe"))
        assertEquals("strasse", TextNormalizer.normalize("Straße"))
        assertEquals("bjork", TextNormalizer.normalize("Björk"))
        assertEquals("sorensen", TextNormalizer.normalize("Sørensen"))
    }

    @Test fun `normalize keeps non-latin scripts`() {
        assertEquals("夜に駆ける", TextNormalizer.normalize("夜に駆ける"))
        assertEquals("привет мир", TextNormalizer.normalize("Привет, Мир!"))
        assertEquals("مرحبا", TextNormalizer.normalize("مرحبا"))
        assertEquals("방탄소년단", TextNormalizer.normalize("방탄소년단"))
    }

    @Test fun `normalize keeps japanese voiced marks`() {
        assertEquals("ありがとう が", TextNormalizer.normalize("ありがとう が"))
        assertEquals("ガ", TextNormalizer.normalize("ｶﾞ"))
    }

    @Test fun `normalize unifies and-tokens`() {
        val expected = "simon and garfunkel"
        assertEquals(expected, TextNormalizer.normalize("Simon & Garfunkel"))
        assertEquals(expected, TextNormalizer.normalize("Simon and Garfunkel"))
        assertEquals(expected, TextNormalizer.normalize("Simon et Garfunkel"))
        assertEquals(expected, TextNormalizer.normalize("Simon + Garfunkel"))
        assertEquals("rock and roll", TextNormalizer.normalize("Rock&Roll"))
    }

    @Test fun `normalize handles punctuation apostrophes and symbols`() {
        assertEquals("dont stop me now", TextNormalizer.normalize("Don't Stop Me Now"))
        assertEquals("dont stop me now", TextNormalizer.normalize("Don’t Stop Me Now"))
        assertEquals("ac dc", TextNormalizer.normalize("AC/DC"))
        assertEquals("suicideboys", TextNormalizer.normalize("\$uicideboy\$"))
        assertEquals("pink", TextNormalizer.normalize("P!nk"))
        assertEquals("panic at the disco", TextNormalizer.normalize("Panic! At The Disco"))
        assertEquals("kesha", TextNormalizer.normalize("Ke\$ha"))
        assertEquals("title", TextNormalizer.normalize("Title 🔥🔥"))
        assertEquals("love", TextNormalizer.normalize("❤️ Love ❤️"))
        assertEquals("a b", TextNormalizer.normalize("  a \t\n  b  "))
    }

    @Test fun `normalize folds fullwidth and ligatures`() {
        assertEquals("abc 123", TextNormalizer.normalize("ＡＢＣ　１２３"))
        assertEquals("fine", TextNormalizer.normalize("ﬁne"))
    }

    @Test fun `normalize of blank and symbol-only text is empty`() {
        assertEquals("", TextNormalizer.normalize(""))
        assertEquals("", TextNormalizer.normalize("   "))
        assertEquals("", TextNormalizer.normalize("🔥"))
        assertEquals("", TextNormalizer.normalize("---"))
    }

    @Test fun `normalizeArtist removes channel suffixes`() {
        assertEquals("theweeknd", TextNormalizer.normalizeArtist("TheWeekndVEVO"))
        assertEquals("adele", TextNormalizer.normalizeArtist("Adele VEVO"))
        assertEquals("queen", TextNormalizer.normalizeArtist("Queen Official"))
        assertEquals("vevo", TextNormalizer.normalizeArtist("VEVO")) // ne jamais renvoyer vide
    }

    // --- stripDecorations ----------------------------------------------------------------------

    @Test fun `stripDecorations removes bracketed noise`() {
        val cases = mapOf(
            "Song (Official Video)" to "Song",
            "Song [Official Music Video]" to "Song",
            "Song (Official Audio)" to "Song",
            "Song (Lyrics)" to "Song",
            "Song (Lyric Video)" to "Song",
            "Song (Audio)" to "Song",
            "Song (Visualizer)" to "Song",
            "Song (Clip officiel)" to "Song",
            "Song (Vidéo officielle)" to "Song",
            "Song (Explicit)" to "Song",
            "Song [HD]" to "Song",
            "Song [4K]" to "Song",
            "Song (Remastered)" to "Song",
            "Song (2011 Remaster)" to "Song",
            "Song (Remastered 2011)" to "Song",
            "Song (with lyrics)" to "Song",
            "Song 【MV】" to "Song",
            "Song (Album Version)" to "Song",
            "Song (Official Video) [4K]" to "Song",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, TextNormalizer.stripDecorations(input)) }
    }

    @Test fun `stripDecorations removes dashed and trailing noise`() {
        assertEquals("Bohemian Rhapsody", TextNormalizer.stripDecorations("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song - 2009 Remaster"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song - Remastered"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song - Single Version"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song | Official Video"))
        assertEquals("Artist - Song", TextNormalizer.stripDecorations("Artist - Song - Remastered 2011"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song HD"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song 4K"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song Official Music Video"))
        assertEquals("Song", TextNormalizer.stripDecorations("Song Lyrics"))
    }

    @Test fun `stripDecorations keeps version tags`() {
        assertEquals("Song - Radio Edit", TextNormalizer.stripDecorations("Song - Radio Edit"))
        assertEquals("Song (Live at Wembley 1986)", TextNormalizer.stripDecorations("Song (Live at Wembley 1986)"))
        assertEquals("Song (Acoustic)", TextNormalizer.stripDecorations("Song (Acoustic) [Official Video]"))
        assertEquals("Song - Remix", TextNormalizer.stripDecorations("Song - Remix"))
        assertEquals("Song (Sped Up)", TextNormalizer.stripDecorations("Song (Sped Up)"))
        assertEquals("Song (Instrumental)", TextNormalizer.stripDecorations("Song (Instrumental)"))
        assertEquals("Song (Karaoke Version)", TextNormalizer.stripDecorations("Song (Karaoke Version)"))
        assertEquals("Song (Extended Mix)", TextNormalizer.stripDecorations("Song (Extended Mix)"))
        assertEquals("Song (Clean Bandit Remix)", TextNormalizer.stripDecorations("Song (Clean Bandit Remix)"))
        assertEquals("Song (Live)", TextNormalizer.stripDecorations("Song (Official Live Video)"))
        assertEquals("Song (Live)", TextNormalizer.stripDecorations("Song (Live 1985 Remastered)"))
    }

    @Test fun `stripDecorations keeps feat groups and meaningful text`() {
        assertEquals("Song (feat. A)", TextNormalizer.stripDecorations("Song (feat. A) (Official Video)"))
        assertEquals("Song (From \"Movie\")", TextNormalizer.stripDecorations("Song (From \"Movie\")"))
        assertEquals("Sound of Music", TextNormalizer.stripDecorations("Sound of Music"))
        assertEquals("Hello", TextNormalizer.stripDecorations("Hello"))
    }

    @Test fun `stripDecorations never returns blank for a non-blank title`() {
        assertEquals("(Official Video)", TextNormalizer.stripDecorations("(Official Video)"))
        assertEquals("", TextNormalizer.stripDecorations(""))
    }

    // --- extractFeaturing ----------------------------------------------------------------------

    @Test fun `extractFeaturing handles bracketed forms`() {
        assertEquals(Featuring("Old Town Road", listOf("Billy Ray Cyrus")), TextNormalizer.extractFeaturing("Old Town Road (feat. Billy Ray Cyrus)"))
        assertEquals(Featuring("Song", listOf("A", "B")), TextNormalizer.extractFeaturing("Song (feat. A & B)"))
        assertEquals(Featuring("Song", listOf("A")), TextNormalizer.extractFeaturing("Song [ft. A]"))
        assertEquals(Featuring("Song", listOf("A")), TextNormalizer.extractFeaturing("Song (Featuring A)"))
        assertEquals(Featuring("Stay", listOf("Justin Bieber")), TextNormalizer.extractFeaturing("Stay (with Justin Bieber)"))
        assertEquals(Featuring("Song", listOf("A", "B", "C")), TextNormalizer.extractFeaturing("Song (feat. A, B and C)"))
    }

    @Test fun `extractFeaturing keeps remaining brackets`() {
        assertEquals(
            Featuring("Old Town Road [Remix]", listOf("Billy Ray Cyrus")),
            TextNormalizer.extractFeaturing("Old Town Road (feat. Billy Ray Cyrus) [Remix]"),
        )
    }

    @Test fun `extractFeaturing handles inline forms`() {
        assertEquals(Featuring("Song", listOf("A")), TextNormalizer.extractFeaturing("Song feat. A"))
        assertEquals(Featuring("Song", listOf("A", "B")), TextNormalizer.extractFeaturing("Song ft. A & B"))
        assertEquals(Featuring("Song - Remix", listOf("A")), TextNormalizer.extractFeaturing("Song feat. A - Remix"))
        assertEquals(Featuring("Song", listOf("Artist X")), TextNormalizer.extractFeaturing("Song FEATURING Artist X"))
    }

    @Test fun `extractFeaturing ignores with outside brackets and lookalike words`() {
        assertEquals(Featuring("Dancing with Myself", emptyList()), TextNormalizer.extractFeaturing("Dancing with Myself"))
        assertEquals(Featuring("Feature Film", emptyList()), TextNormalizer.extractFeaturing("Feature Film"))
        assertEquals(Featuring("Loft Music", emptyList()), TextNormalizer.extractFeaturing("Loft Music"))
        assertEquals(Featuring("Plain", emptyList()), TextNormalizer.extractFeaturing("Plain"))
    }

    // --- splitArtists --------------------------------------------------------------------------

    @Test fun `splitArtists splits common separators`() {
        assertEquals(listOf("A", "B", "C", "D"), TextNormalizer.splitArtists("A, B & C feat. D"))
        assertEquals(listOf("A", "B"), TextNormalizer.splitArtists("A and B"))
        assertEquals(listOf("A", "B"), TextNormalizer.splitArtists("A x B"))
        assertEquals(listOf("A", "B"), TextNormalizer.splitArtists("A ; B"))
        assertEquals(listOf("A", "B"), TextNormalizer.splitArtists("A (feat. B)"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), TextNormalizer.splitArtists("Daft Punk et Pharrell Williams"))
    }

    @Test fun `splitArtists keeps single names intact`() {
        assertEquals(listOf("AC/DC"), TextNormalizer.splitArtists("AC/DC"))
        assertEquals(listOf("Lil Nas X"), TextNormalizer.splitArtists("Lil Nas X"))
        assertEquals(listOf("Alexander"), TextNormalizer.splitArtists("Alexander"))
        assertEquals(listOf("Sandra"), TextNormalizer.splitArtists("Sandra"))
        assertTrue(TextNormalizer.splitArtists("").isEmpty())
    }

    @Test fun `splitArtists removes duplicates`() {
        assertEquals(listOf("A", "B"), TextNormalizer.splitArtists("A & B feat. a"))
    }

    // --- versionTags ---------------------------------------------------------------------------

    @Test fun `versionTags detects every tag`() {
        val cases = mapOf(
            "Song (Live at Wembley)" to setOf("live"),
            "Song (Acoustic)" to setOf("acoustic"),
            "Song MTV Unplugged" to setOf("acoustic"),
            "Song - Remix" to setOf("remix"),
            "Song (Club Remixed)" to setOf("remix"),
            "Song (Instrumental)" to setOf("instrumental"),
            "Song (Cover)" to setOf("cover"),
            "Song (Karaoke Version)" to setOf("karaoke"),
            "Song (Originally Performed by X)" to setOf("karaoke"),
            "Song (Sped Up)" to setOf("sped up"),
            "Song sped-up version" to setOf("sped up"),
            "Song (Slowed + Reverb)" to setOf("slowed"),
            "Song Nightcore" to setOf("nightcore"),
            "Song (Extended Mix)" to setOf("extended"),
            "Song - Radio Edit" to setOf("radio edit"),
            "Song (Demo)" to setOf("demo"),
            "Song (Reprise)" to setOf("reprise"),
            "Song (A Cappella)" to setOf("acapella"),
            "Song (Acapella)" to setOf("acapella"),
            "Song (8D Audio)" to setOf("8d"),
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, TextNormalizer.versionTags(input)) }
    }

    @Test fun `versionTags detects several tags and none`() {
        assertEquals(setOf("live", "acoustic"), TextNormalizer.versionTags("Song (Live Acoustic Session)"))
        assertEquals(emptySet<String>(), TextNormalizer.versionTags("Bohemian Rhapsody"))
        assertEquals(emptySet<String>(), TextNormalizer.versionTags(""))
    }

    @Test fun `versionTags respects word boundaries`() {
        assertEquals(emptySet<String>(), TextNormalizer.versionTags("Alive"))
        assertEquals(emptySet<String>(), TextNormalizer.versionTags("Delivered Demolition"))
        assertEquals(emptySet<String>(), TextNormalizer.versionTags("Discovery"))
    }
}
