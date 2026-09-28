package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaylistFileParsersTest {
    private val parsers = PlaylistFileParsers()

    private fun parse(name: String?, fixture: String): List<ImportedPlaylist> =
        parsers.parse(name, ImportFixtures.bytes(fixture))

    private fun assertRejected(name: String?, bytes: ByteArray, expectedInReason: String? = null) {
        try {
            parsers.parse(name, bytes)
            fail("ImportParseException attendue")
        } catch (e: ImportParseException) {
            assertTrue(e.reason.isNotBlank())
            if (expectedInReason != null) assertTrue(e.reason, e.reason.contains(expectedInReason))
        }
    }

    @Test
    fun `registry picks the right parser for each fixture`() {
        fun format(name: String, fixture: String) = parsers.detect(name, ImportFixtures.text(fixture))?.format
        assertEquals(ImportFormat.EXPORTIFY_CSV, format("Road_trip.csv", "exportify_current.csv"))
        assertEquals(ImportFormat.EXPORTIFY_CSV, format("liked.csv", "exportify_old.csv"))
        assertEquals(ImportFormat.SPOTIFY_JSON, format("Playlist1.json", "Playlist1.json"))
        assertEquals(ImportFormat.SPOTIFY_JSON, format("YourLibrary.json", "YourLibrary.json"))
        assertEquals(ImportFormat.TAKEOUT_CSV, format("Favoris-videos.csv", "takeout_new-videos.csv"))
        assertEquals(ImportFormat.TAKEOUT_CSV, format("Liked videos.csv", "takeout_old.csv"))
        assertEquals(ImportFormat.GENERIC_CSV, format("chansons.csv", "generic_fr_semicolon_utf8.csv"))
        assertEquals(ImportFormat.GENERIC_JSON, format("tracks.json", "tracks.json"))
        assertEquals(ImportFormat.GENERIC_JSON, format("playlist.json", "playlist_object.json"))
        assertNull(parsers.detect("garbage.txt", ImportFixtures.text("garbage.txt")))
    }

    @Test
    fun `exportify file end to end`() {
        val playlist = parse("Road_trip.csv", "exportify_current.csv").single()
        assertEquals("Road trip", playlist.name)
        assertEquals(ImportFormat.EXPORTIFY_CSV, playlist.sourceFormat)
        assertEquals(7, playlist.tracks.size)
        assertEquals("夜に駆ける", playlist.tracks[5].title)
    }

    @Test
    fun `works without a file name`() {
        assertEquals(7, parse(null, "exportify_current.csv").single().tracks.size)
        assertEquals(2, parse(null, "Playlist1.json").size)
        assertEquals(4, parse(null, "takeout_new-videos.csv").single().tracks.size)
    }

    @Test
    fun `UTF-8 UTF-8 BOM and UTF-16 files give identical results`() {
        val reference = parse("Mes_chansons.csv", "generic_fr_semicolon_utf8.csv")
        assertEquals(6, reference.single().tracks.size)
        assertEquals(reference, parse("Mes_chansons.csv", "generic_fr_utf8_bom.csv"))
        assertEquals(reference, parse("Mes_chansons.csv", "generic_fr_utf16le_bom.csv"))
    }

    @Test
    fun `Windows-1252 encoded file keeps its accents`() {
        val playlist = parse("Ancien_export.csv", "generic_fr_cp1252.csv").single()
        assertEquals("Ancien export", playlist.name)
        assertEquals(
            listOf(
                track("Été", "Céline Dion", album = "Où êtes-vous ?", durationMs = 185_000),
                track("Ça plane pour moi", "Plastic Bertrand", album = "Ça plane pour moi", durationMs = 200_000),
                track("Mélancolie « œuvre »", "Zoë Français", album = "Naïve – Vol. 2", durationMs = 250_000),
            ),
            playlist.tracks,
        )
    }

    @Test
    fun `CRLF files parse like LF files`() {
        for ((name, fixture) in listOf(
            "a.csv" to "exportify_current.csv",
            "b.csv" to "generic_fr_semicolon_utf8.csv",
            "c-videos.csv" to "takeout_new-videos.csv",
            "d.csv" to "takeout_old.csv",
            "e.json" to "Playlist1.json",
            "f.json" to "tracks.json",
        )) {
            val lf = ImportFixtures.text(fixture)
            assertEquals(name, parsers.parse(name, lf), parsers.parse(name, lf.replace("\n", "\r\n")))
        }
    }

    @Test
    fun `Spotify and Takeout fixtures end to end`() {
        assertEquals(listOf(3, 1), parse("Playlist1.json", "Playlist1.json").map { it.tracks.size })
        assertEquals(listOf("Titres likés (Spotify)"), parse("YourLibrary.json", "YourLibrary.json").map { it.name })
        val takeout = parse("Takeout/YouTube et YouTube Music/playlists/Sons du dimanche-videos.csv", "takeout_new-videos.csv").single()
        assertEquals("Sons du dimanche", takeout.name)
        assertEquals(listOf("dQw4w9WgXcQ", "9bZkp7q19f0", "kJQP7kiw5Fk", "_-abcDEF123"), takeout.tracks.map { it.youtubeId })
    }

    @Test
    fun `garbage text is rejected with a French message`() {
        assertRejected("garbage.txt", ImportFixtures.bytes("garbage.txt"), "Format de fichier non reconnu")
    }

    @Test
    fun `binary garbage is rejected`() {
        assertRejected("photo.csv", ByteArray(256) { (it * 37 + 11).toByte() })
    }

    @Test
    fun `empty and blank files are rejected`() {
        assertRejected("x.csv", ByteArray(0), "vide")
        assertRejected("x.csv", "  \n\r\n\t ".toByteArray(), "vide")
    }

    @Test
    fun `files with a recognised header but no usable row are rejected`() {
        assertRejected("x.csv", "Titre;Artiste;Album\n;;\n".toByteArray(), "Aucun titre")
        assertRejected("x.json", """{"playlists":[{"name":"vide","items":[]}]}""".toByteArray(), "Aucun titre")
    }

    @Test
    fun `unrelated or malformed JSON and CSV are rejected`() {
        assertRejected("x.json", """{"foo": "bar"}""".toByteArray())
        assertRejected("x.json", """[{"note": "x"}]""".toByteArray())
        assertRejected("x.json", "{ oops".toByteArray())
        assertRejected("x.csv", "Foo,Bar\n1,2\n".toByteArray())
        assertRejected("x.csv", "Title,Artist\n\"unclosed,A\n".toByteArray())
    }

    @Test
    fun `empty playlists are filtered out but others kept`() {
        val json = """{"playlists":[{"name":"vide","items":[]},{"name":"ok","items":[{"track":{"trackName":"A","artistName":"B"}}]}]}"""
        assertEquals(listOf("ok"), parsers.parse("Playlist1.json", json).map { it.name })
    }

    @Test
    fun `parser exceptions other than ImportParseException are wrapped`() {
        val exploding = object : PlaylistFileParser {
            override val format = ImportFormat.GENERIC_CSV
            override fun canParse(fileName: String?, content: String) = true
            override fun parse(fileName: String?, content: String): List<ImportedPlaylist> = error("boom")
        }
        try {
            PlaylistFileParsers(listOf(exploding)).parse("x", "abc")
            fail("ImportParseException attendue")
        } catch (e: ImportParseException) {
            assertNotNull(e.cause)
        }
    }

    @Test
    fun `canParse that throws is treated as not matching`() {
        val throwing = object : PlaylistFileParser {
            override val format = ImportFormat.GENERIC_CSV
            override fun canParse(fileName: String?, content: String): Boolean = error("boom")
            override fun parse(fileName: String?, content: String): List<ImportedPlaylist> = emptyList()
        }
        val registry = PlaylistFileParsers(listOf(throwing) + PlaylistFileParsers.defaultParsers())
        assertEquals(2, registry.parse("x.csv", "Titre,Artiste\nA,B\nC,D\n").single().tracks.size)
    }

    @Test
    fun `default order is Exportify Spotify Takeout GenericCsv GenericJson`() {
        assertEquals(
            listOf(
                ImportFormat.EXPORTIFY_CSV, ImportFormat.SPOTIFY_JSON, ImportFormat.TAKEOUT_CSV,
                ImportFormat.GENERIC_CSV, ImportFormat.GENERIC_JSON,
            ),
            PlaylistFileParsers.defaultParsers().map { it.format },
        )
    }
}
