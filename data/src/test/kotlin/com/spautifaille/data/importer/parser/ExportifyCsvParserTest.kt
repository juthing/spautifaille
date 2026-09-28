package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExportifyCsvParserTest {
    private val parser = ExportifyCsvParser()

    @Test
    fun `parses the current Exportify export`() {
        val playlists = parser.parse("My_Road_Trip_2024.csv", ImportFixtures.text("exportify_current.csv"))

        assertEquals(1, playlists.size)
        val playlist = playlists.single()
        assertEquals("My Road Trip 2024", playlist.name)
        assertEquals(ImportFormat.EXPORTIFY_CSV, playlist.sourceFormat)
        assertEquals(
            listOf(
                track("Never Gonna Give You Up", "Rick Astley", album = "Whenever You Need Somebody", durationMs = 213_573, isrc = "GBARL9300135"),
                track("Hello, Goodbye - Remastered 2009", "The Beatles", album = "Magical Mystery Tour (Remastered)", durationMs = 207_813, isrc = "GBUM71505893"),
                track("EARFQUAKE", "Tyler, The Creator", "Playboi Carti", album = "IGOR", durationMs = 198_000, isrc = "USSM11901234"),
                track("Blinding Lights", "The Weeknd", "ZAYN", album = "After Hours", durationMs = 200_040, isrc = "USUG11904206"),
                track("Café del Mar ☀️ (Été 2020) 🎧", "Énergie Pure", album = "Souvenirs d'été", durationMs = 305_120, isrc = "FRZ012000042"),
                track("夜に駆ける", "YOASOBI", album = "THE BOOK", durationMs = 261_000, isrc = "JPPO02000010"),
                track("Titre sans album ni ISRC", "Band of Horses"),
            ),
            playlist.tracks,
        )
    }

    @Test
    fun `unescapes commas inside artist names and splits on the others`() {
        val artists = parser.parse("x.csv", ImportFixtures.text("exportify_current.csv")).single().tracks[2].artists
        assertEquals(listOf("Tyler, The Creator", "Playboi Carti"), artists)
    }

    @Test
    fun `parses the old Exportify variant with Spotify ID and Duration (ms)`() {
        val content = ImportFixtures.text("exportify_old.csv")
        assertTrue(parser.canParse("liked_songs.csv", content))
        val playlist = parser.parse("liked_songs.csv", content).single()

        assertEquals("liked songs", playlist.name)
        assertEquals(
            listOf(
                track("Never Gonna Give You Up", "Rick Astley", album = "Whenever You Need Somebody", durationMs = 213_573),
                track("Blinding Lights, Live", "The Weeknd", "ZAYN", album = "After Hours", durationMs = 200_040),
            ),
            playlist.tracks,
        )
    }

    @Test
    fun `singular Artist Name header and no ISRC column`() {
        val csv = "Track URI,Track Name,Artist Name,Album Name,Track Duration (ms)\n" +
            "spotify:track:abc,Song A,Artist A,Album A,180000\n"
        val track = parser.parse("p.csv", csv).single().tracks.single()
        assertEquals(track("Song A", "Artist A", album = "Album A", durationMs = 180_000), track)
    }

    @Test
    fun `optional audio feature columns are ignored`() {
        val csv = "Track URI,Track Name,Artist Name(s),Album Name,Track Duration (ms),Danceability,Energy,Key,Tempo\n" +
            "spotify:track:abc,Song A,Artist A,Album A,180000,0.7,0.8,5,120.1\n"
        assertEquals(track("Song A", "Artist A", album = "Album A", durationMs = 180_000), parser.parse(null, csv).single().tracks.single())
    }

    @Test
    fun `CRLF line endings give the same result as LF`() {
        val lf = ImportFixtures.text("exportify_current.csv")
        val crlf = lf.replace("\n", "\r\n")
        assertTrue(parser.canParse("a.csv", crlf))
        assertEquals(parser.parse("a.csv", lf), parser.parse("a.csv", crlf))
    }

    @Test
    fun `tracks without a name are dropped but local tracks with empty uri are kept`() {
        val csv = "Track URI,Track Name,Artist Name(s),Track Duration (ms)\n" +
            "spotify:track:1,,Nobody,1000\n" +
            ",Local Song,Local Artist,120000\n" +
            ",,,\n"
        val tracks = parser.parse("p.csv", csv).single().tracks
        assertEquals(listOf(track("Local Song", "Local Artist", durationMs = 120_000)), tracks)
    }

    @Test
    fun `values are trimmed`() {
        val csv = "Track URI,Track Name,Artist Name(s)\nspotify:track:1,  Padded Title  ,  A ,  B  \n"
        val t = parser.parse("p.csv", csv).single().tracks.single()
        assertEquals("Padded Title", t.title)
    }

    @Test
    fun `falls back to a default name without file name`() {
        val csv = "Track URI,Track Name\nspotify:track:1,Song\n"
        assertEquals("Playlist Spotify", parser.parse(null, csv).single().name)
    }

    @Test
    fun `canParse only accepts Exportify headers`() {
        assertTrue(parser.canParse("x.csv", ImportFixtures.text("exportify_current.csv")))
        assertTrue(parser.canParse(null, ImportFixtures.text("exportify_current.csv")))
        assertFalse(parser.canParse("x.csv", ImportFixtures.text("generic_fr_semicolon_utf8.csv")))
        assertFalse(parser.canParse("x.csv", ImportFixtures.text("takeout_new-videos.csv")))
        assertFalse(parser.canParse("x.csv", "Track Name,Artist Name(s)\nA,B\n"))
        assertFalse(parser.canParse("x.json", ImportFixtures.text("exportify_current.csv")))
        assertFalse(parser.canParse("x.csv", ""))
    }

    @Test
    fun `parse throws a clear error on a foreign header`() {
        try {
            parser.parse("x.csv", "Titre;Artiste\nA;B\n")
            fail("ImportParseException attendue")
        } catch (e: ImportParseException) {
            assertTrue(e.reason.contains("Exportify"))
        }
    }

    @Test
    fun `no isrc yields null`() {
        val t = parser.parse("x.csv", ImportFixtures.text("exportify_current.csv")).single().tracks.last()
        assertNull(t.isrc)
        assertNull(t.durationMs)
        assertNull(t.album)
    }
}
