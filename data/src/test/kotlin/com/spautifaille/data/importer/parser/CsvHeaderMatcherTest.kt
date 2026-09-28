package com.spautifaille.data.importer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvHeaderMatcherTest {

    @Test
    fun `normalize strips BOM quotes case diacritics and punctuation`() {
        assertEquals("duree ms", CsvHeaderMatcher.normalize("﻿\"Durée (ms)\""))
        assertEquals("artist name s", CsvHeaderMatcher.normalize("  Artist Name(s) "))
        assertEquals("nom de l artiste", CsvHeaderMatcher.normalize("Nom de l'artiste"))
        assertEquals("interprete", CsvHeaderMatcher.normalize("INTERPRÈTE"))
        assertEquals("影片 id", CsvHeaderMatcher.normalize("影片 ID"))
    }

    @Test
    fun `fieldOf handles English French Spanish German Italian aliases`() {
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("Track Name"))
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("Titre"))
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("Morceau"))
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("Chanson"))
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("Título"))
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("Titel"))
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("Titolo"))
        assertEquals(ImportField.ARTIST, CsvHeaderMatcher.fieldOf("Artist Name(s)"))
        assertEquals(ImportField.ARTIST, CsvHeaderMatcher.fieldOf("Artistes"))
        assertEquals(ImportField.ARTIST, CsvHeaderMatcher.fieldOf("Interprète"))
        assertEquals(ImportField.ARTIST, CsvHeaderMatcher.fieldOf("Künstler"))
        assertEquals(ImportField.ALBUM, CsvHeaderMatcher.fieldOf("Album Name"))
        assertEquals(ImportField.DURATION, CsvHeaderMatcher.fieldOf("Track Duration (ms)"))
        assertEquals(ImportField.DURATION, CsvHeaderMatcher.fieldOf("Durée"))
        assertEquals(ImportField.DURATION, CsvHeaderMatcher.fieldOf("Length"))
        assertEquals(ImportField.ISRC, CsvHeaderMatcher.fieldOf("ISRC"))
        assertEquals(ImportField.YOUTUBE, CsvHeaderMatcher.fieldOf("Video ID"))
        assertEquals(ImportField.YOUTUBE, CsvHeaderMatcher.fieldOf("Lien"))
        assertNull(CsvHeaderMatcher.fieldOf("Album Artist Name(s)"))
        assertNull(CsvHeaderMatcher.fieldOf("Added At"))
    }

    @Test
    fun `fieldOf is insensitive to camelCase and snake_case JSON keys`() {
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("trackName"))
        assertEquals(ImportField.TITLE, CsvHeaderMatcher.fieldOf("track_name"))
        assertEquals(ImportField.ARTIST, CsvHeaderMatcher.fieldOf("artistName"))
        assertEquals(ImportField.DURATION, CsvHeaderMatcher.fieldOf("durationMs"))
        assertEquals(ImportField.DURATION, CsvHeaderMatcher.fieldOf("duration_ms"))
        assertEquals(ImportField.YOUTUBE, CsvHeaderMatcher.fieldOf("youtubeId"))
    }

    @Test
    fun `resolve maps the current Exportify header`() {
        val header = listOf(
            "Track URI", "Track Name", "Artist URI(s)", "Artist Name(s)", "Album URI", "Album Name",
            "Album Artist URI(s)", "Album Artist Name(s)", "Album Release Date", "Album Image URL", "Disc Number",
            "Track Number", "Track Duration (ms)", "Track Preview URL", "Explicit", "Popularity", "ISRC",
            "Added By", "Added At",
        )
        val columns = CsvHeaderMatcher.resolve(header)
        assertEquals(1, columns[ImportField.TITLE])
        assertEquals(3, columns[ImportField.ARTIST])
        assertEquals(5, columns[ImportField.ALBUM])
        assertEquals(12, columns[ImportField.DURATION])
        assertEquals(16, columns[ImportField.ISRC])
        assertNull(columns[ImportField.YOUTUBE])
    }

    @Test
    fun `resolve prefers Track Name over generic Name and never reuses a column`() {
        val columns = CsvHeaderMatcher.resolve(listOf("Name", "Track Name", "Artist"))
        assertEquals(1, columns[ImportField.TITLE])
        assertEquals(2, columns[ImportField.ARTIST])
    }

    @Test
    fun `resolve maps a French header`() {
        val columns = CsvHeaderMatcher.resolve(listOf("Titre", "Artiste", "Album", "Durée"))
        assertEquals(mapOf(
            ImportField.TITLE to 0, ImportField.ARTIST to 1, ImportField.ALBUM to 2, ImportField.DURATION to 3,
        ), columns)
    }

    @Test
    fun `duration unit hints`() {
        assertEquals(DurationUnit.MILLISECONDS, CsvHeaderMatcher.durationUnitHint("Track Duration (ms)"))
        assertEquals(DurationUnit.MILLISECONDS, CsvHeaderMatcher.durationUnitHint("durationMs"))
        assertEquals(DurationUnit.SECONDS, CsvHeaderMatcher.durationUnitHint("Durée (s)"))
        assertEquals(DurationUnit.SECONDS, CsvHeaderMatcher.durationUnitHint("durationSeconds"))
        assertNull(CsvHeaderMatcher.durationUnitHint("Durée"))
        assertNull(CsvHeaderMatcher.durationUnitHint("Duration"))
    }

    // ---- Durées ----

    @Test
    fun `parses mm ss and h mm ss durations`() {
        assertEquals(225_000L, DurationParser.parse("3:45"))
        assertEquals(3_723_000L, DurationParser.parse("1:02:03"))
        assertEquals(59_000L, DurationParser.parse("0:59"))
    }

    @Test
    fun `parses ISO 8601 durations`() {
        assertEquals(225_000L, DurationParser.parse("PT3M45S"))
        assertEquals(3_600_000L, DurationParser.parse("PT1H"))
    }

    @Test
    fun `numeric duration auto-detects ms versus seconds`() {
        assertEquals(213_573L, DurationParser.parse("213573"))
        assertEquals(225_000L, DurationParser.parse("225"))
        assertEquals(225_000L, DurationParser.parse("225", DurationUnit.SECONDS))
        assertEquals(225L, DurationParser.parse("225", DurationUnit.MILLISECONDS))
        assertEquals(225_500L, DurationParser.parse("225.5"))
        assertEquals(225_000L, DurationParser.parse("225 000"))
    }

    @Test
    fun `column detection uses the median so one long track does not flip the unit`() {
        val seconds = DurationParser.parseColumn(listOf("180", "240", "12000", "200"), null)
        assertEquals(listOf(180_000L, 240_000L, 12_000_000L, 200_000L), seconds)
        val ms = DurationParser.parseColumn(listOf("180000", "240000", "3000"), null)
        assertEquals(listOf(180_000L, 240_000L, 3_000L), ms)
    }

    @Test
    fun `invalid empty or non positive durations are null`() {
        assertNull(DurationParser.parse(""))
        assertNull(DurationParser.parse("abc"))
        assertNull(DurationParser.parse("0"))
        assertNull(DurationParser.parse("-5"))
        assertEquals(listOf(null, null, 120_000L), DurationParser.parseColumn(listOf("", "x", "2:00"), null))
    }

    // ---- Ids YouTube ----

    @Test
    fun `extracts YouTube id from every URL variant`() {
        val id = "dQw4w9WgXcQ"
        val variants = listOf(
            "dQw4w9WgXcQ",
            "  dQw4w9WgXcQ  ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "http://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abcdef",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share",
            "WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ",
        )
        for (v in variants) assertEquals("variante $v", id, YoutubeIds.extract(v))
    }

    @Test
    fun `keeps ids with dash and underscore`() {
        assertEquals("_-abcDEF123", YoutubeIds.extract("https://youtu.be/_-abcDEF123"))
    }

    @Test
    fun `rejects non video URLs and malformed ids`() {
        assertNull(YoutubeIds.extract("https://www.youtube.com/playlist?list=PLbpi6ZahtOH6Blw3RGYpWkSByi_T7Rygb"))
        assertNull(YoutubeIds.extract("https://www.youtube.com/watch?v=short"))
        assertNull(YoutubeIds.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQtoolong"))
        assertNull(YoutubeIds.extract("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertNull(YoutubeIds.extract(""))
        assertNull(YoutubeIds.extract(null))
        assertNull(YoutubeIds.extract("Never Gonna Give You Up"))
    }

    @Test
    fun `looksRandom distinguishes real ids from eleven letter words`() {
        assertTrue(YoutubeIds.looksRandom("dQw4w9WgXcQ"))
        assertTrue(YoutubeIds.looksRandom("kJQP7kiw5Fk"))
        assertFalse(YoutubeIds.looksRandom("Radioactive"))
        assertFalse(YoutubeIds.looksRandom("Identifiant"))
    }
}
