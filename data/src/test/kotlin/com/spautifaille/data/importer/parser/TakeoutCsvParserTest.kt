package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoutCsvParserTest {
    private val parser = TakeoutCsvParser()

    private fun ids(vararg id: String) = id.map { ImportedTrack(title = "", youtubeId = it) }

    @Test
    fun `parses the current Takeout format`() {
        val path = "Takeout/YouTube et YouTube Music/playlists/Sons du dimanche-videos.csv"
        val playlist = parser.parse(path, ImportFixtures.text("takeout_new-videos.csv")).single()

        assertEquals("Sons du dimanche", playlist.name)
        assertEquals(ImportFormat.TAKEOUT_CSV, playlist.sourceFormat)
        assertEquals(ids("dQw4w9WgXcQ", "9bZkp7q19f0", "kJQP7kiw5Fk", "_-abcDEF123"), playlist.tracks)
        assertTrue(playlist.tracks.all { it.title.isEmpty() && it.artists.isEmpty() })
    }

    @Test
    fun `playlist name keeps underscores and only strips the videos suffix`() {
        val content = ImportFixtures.text("takeout_new-videos.csv")
        assertEquals("takeout_new", parser.parse("takeout_new-videos.csv", content).single().name)
        assertEquals("Liked videos", parser.parse("Liked videos.csv", content).single().name)
        assertEquals("Ma Playlist", parser.parse("Ma Playlist-Vidéos.csv", content).single().name)
        assertEquals("Playlist YouTube", parser.parse(null, content).single().name)
    }

    @Test
    fun `parses the old format and reads the title from the metadata rows`() {
        val content = ImportFixtures.text("takeout_old.csv")
        assertTrue(parser.canParse("Liked videos.csv", content))
        val playlist = parser.parse("Liked videos.csv", content).single()

        assertEquals("Sons du dimanche", playlist.name)
        assertEquals(ids("dQw4w9WgXcQ", "9bZkp7q19f0"), playlist.tracks)
    }

    @Test
    fun `detects a French or unknown localized header positionally`() {
        val csv = "Identifiant,Horodatage de création\n" +
            "dQw4w9WgXcQ,2024-06-24T23:49:51+00:00\n" +
            "9bZkp7q19f0,2023-01-05T10:00:00+00:00\n"
        assertTrue(parser.canParse("Favoris-videos.csv", csv))
        assertEquals(ids("dQw4w9WgXcQ", "9bZkp7q19f0"), parser.parse("Favoris-videos.csv", csv).single().tracks)
    }

    @Test
    fun `recognises the localized Chinese header`() {
        val csv = "影片 ID,播放清單影片的建立時間戳記\ndKMTzrG92TE,2018-04-24T09:05:28+00:00\n"
        assertTrue(parser.canParse("x.csv", csv))
        assertEquals(ids("dKMTzrG92TE"), parser.parse("x.csv", csv).single().tracks)
    }

    @Test
    fun `header case is ignored`() {
        val csv = "video id,playlist video creation timestamp\ndQw4w9WgXcQ,2024-06-24T23:49:51+00:00\n"
        assertEquals(ids("dQw4w9WgXcQ"), parser.parse("x.csv", csv).single().tracks)
    }

    @Test
    fun `headerless file under a playlists folder uses column 0`() {
        val csv = "dQw4w9WgXcQ\n9bZkp7q19f0\n"
        assertTrue(parser.canParse("Takeout/YouTube/playlists/a.csv", csv))
        assertEquals(ids("dQw4w9WgXcQ", "9bZkp7q19f0"), parser.parse("Takeout/YouTube/playlists/a.csv", csv).single().tracks)
    }

    @Test
    fun `invalid ids and blank rows are ignored`() {
        val csv = "Video ID,Playlist Video Creation Timestamp\n" +
            "dQw4w9WgXcQ,2024-06-24T23:49:51+00:00\n" +
            "tooshort,2024-06-24T23:49:51+00:00\n" +
            ",\n" +
            "https://youtu.be/9bZkp7q19f0,2024-06-24T23:49:51+00:00\n"
        assertEquals(ids("dQw4w9WgXcQ", "9bZkp7q19f0"), parser.parse("x.csv", csv).single().tracks)
    }

    @Test
    fun `CRLF line endings are handled`() {
        val csv = ImportFixtures.text("takeout_old.csv").replace("\n", "\r\n")
        val playlist = parser.parse("x.csv", csv).single()
        assertEquals("Sons du dimanche", playlist.name)
        assertEquals(2, playlist.tracks.size)
    }

    @Test
    fun `does not claim other CSV formats`() {
        assertFalse(parser.canParse("x.csv", ImportFixtures.text("exportify_current.csv")))
        assertFalse(parser.canParse("x.csv", ImportFixtures.text("generic_fr_semicolon_utf8.csv")))
        assertFalse(parser.canParse("x.csv", "Video ID,Title,Artist\ndQw4w9WgXcQ,Never Gonna Give You Up,Rick Astley\n"))
        assertFalse(parser.canParse("x.json", ImportFixtures.text("takeout_new-videos.csv")))
        // playlists.csv de Takeout : métadonnées seules, aucune vidéo
        assertFalse(parser.canParse("playlists.csv", "Playlist ID,Add new videos to top,Playlist Title (Original)\nPL123,False,Test\n"))
        // Une colonne de mots de 11 lettres n'est pas une colonne d'ids
        assertFalse(parser.canParse("x.csv", "Radioactive\nHelloWorld1\n"))
    }
}
