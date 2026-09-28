package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericCsvParserTest {
    private val parser = GenericCsvParser()

    private val frenchExpected = listOf(
        track("Alors on danse", "Stromae", album = "Cheese", durationMs = 207_000),
        track("Papaoutai", "Stromae", album = "Racine carrée", durationMs = 232_000),
        track("La Vie en rose; version studio", "Édith Piaf", album = "La Môme", durationMs = 188_000),
        track("Foule sentimentale", "Alain Souchon", "Laurent Voulzy", album = "Défoule sentimentale", durationMs = 289_000),
        track("Les Champs-Élysées", "Joe Dassin", durationMs = 3_723_000),
        track("Sans durée", "Artiste inconnu", album = "Album X"),
    )

    @Test
    fun `parses a semicolon delimited French CSV with mm ss durations`() {
        val content = ImportFixtures.text("generic_fr_semicolon_utf8.csv")
        assertTrue(parser.canParse("Mes_chansons.csv", content))
        val playlist = parser.parse("Mes_chansons.csv", content).single()

        assertEquals("Mes chansons", playlist.name)
        assertEquals(ImportFormat.GENERIC_CSV, playlist.sourceFormat)
        assertEquals(frenchExpected, playlist.tracks)
    }

    @Test
    fun `quoted cells may contain the delimiter`() {
        val track = parser.parse("x.csv", ImportFixtures.text("generic_fr_semicolon_utf8.csv")).single().tracks[2]
        assertEquals("La Vie en rose; version studio", track.title)
    }

    @Test
    fun `parses a tab separated file`() {
        val tsv = "Title\tArtist\tAlbum\tDuration\nSong A\tArtist A\tAlbum A\t215000\nSong B\tArtist B\t\t198000\n"
        val tracks = parser.parse("x.tsv", tsv).single().tracks
        assertEquals(
            listOf(
                track("Song A", "Artist A", album = "Album A", durationMs = 215_000),
                track("Song B", "Artist B", durationMs = 198_000),
            ),
            tracks,
        )
    }

    @Test
    fun `seconds durations are detected from small numbers`() {
        val csv = "Title,Artist,Duration\nSong A,Artist A,215\nSong B,Artist B,198\n"
        assertEquals(listOf(215_000L, 198_000L), parser.parse("x.csv", csv).single().tracks.map { it.durationMs })
    }

    @Test
    fun `column name hint overrides auto detection`() {
        val csv = "Title,Artist,Durée (s)\nSong A,Artist A,215\n"
        assertEquals(215_000L, parser.parse("x.csv", csv).single().tracks.single().durationMs)
        val ms = "Title,Artist,Duration (ms)\nSong A,Artist A,215\n"
        assertEquals(215L, parser.parse("x.csv", ms).single().tracks.single().durationMs)
    }

    @Test
    fun `extracts YouTube ids from URL column`() {
        val csv = "Titre,Lien\n" +
            "A,https://www.youtube.com/watch?v=dQw4w9WgXcQ\n" +
            "B,https://youtu.be/9bZkp7q19f0?si=xyz\n" +
            "C,https://music.youtube.com/watch?v=kJQP7kiw5Fk&list=RDAMVM\n" +
            "D,https://www.youtube.com/shorts/_-abcDEF123\n" +
            "E,pas un lien\n"
        assertEquals(
            listOf("dQw4w9WgXcQ", "9bZkp7q19f0", "kJQP7kiw5Fk", "_-abcDEF123", null),
            parser.parse("x.csv", csv).single().tracks.map { it.youtubeId },
        )
    }

    @Test
    fun `URL column alone is enough and yields id only tracks`() {
        val csv = "URL\nhttps://youtu.be/dQw4w9WgXcQ\nhttps://example.com/nothing\n"
        assertTrue(parser.canParse("liens.csv", csv))
        val tracks = parser.parse("liens.csv", csv).single().tracks
        assertEquals(listOf(track("", youtubeId = "dQw4w9WgXcQ")), tracks)
    }

    @Test
    fun `video id column with a title is a generic CSV with title and id`() {
        val csv = "Video ID,Title,Artist\ndQw4w9WgXcQ,Never Gonna Give You Up,Rick Astley\n"
        assertTrue(parser.canParse("x.csv", csv))
        assertEquals(
            listOf(track("Never Gonna Give You Up", "Rick Astley", youtubeId = "dQw4w9WgXcQ")),
            parser.parse("x.csv", csv).single().tracks,
        )
    }

    @Test
    fun `skips leading junk rows before the header and ignores empty rows`() {
        val csv = "Export du 12/03/2024\n\nTitre,Artiste\n,\nSong A,Artist A\n   ,   \nSong B,Artist B\n"
        assertEquals(
            listOf(track("Song A", "Artist A"), track("Song B", "Artist B")),
            parser.parse("x.csv", csv).single().tracks,
        )
    }

    @Test
    fun `rows without title nor id are dropped and values trimmed`() {
        val csv = "Title,Artist\n  Song A  ,  Artist A  \n,Artist only\n"
        assertEquals(listOf(track("Song A", "Artist A")), parser.parse("x.csv", csv).single().tracks)
    }

    @Test
    fun `CRLF and ISRC column`() {
        val csv = "Title,Artist,ISRC\r\nSong A,Artist A,gbarl9300135\r\nSong B,Artist B,\r\n"
        assertEquals(
            listOf(track("Song A", "Artist A", isrc = "GBARL9300135"), track("Song B", "Artist B")),
            parser.parse("x.csv", csv).single().tracks,
        )
    }

    @Test
    fun `slash and semicolon split artists but comma does not`() {
        val csv = "Title,Artist\nA,\"Tyler, The Creator\"\nB,AC/DC\nC,\"X / Y\"\nD,\"P;Q\"\n"
        assertEquals(
            listOf(listOf("Tyler, The Creator"), listOf("AC/DC"), listOf("X", "Y"), listOf("P", "Q")),
            parser.parse("x.csv", csv).single().tracks.map { it.artists },
        )
    }

    @Test
    fun `rejects CSV without title or link columns and JSON content`() {
        assertFalse(parser.canParse("x.csv", "Foo,Bar\n1,2\n"))
        assertFalse(parser.canParse("x.csv", ImportFixtures.text("tracks.json")))
        assertFalse(parser.canParse("x.json", "Title,Artist\nA,B\n"))
        assertFalse(parser.canParse("x.csv", ""))
    }

    @Test
    fun `unbalanced quotes give a clear error`() {
        val csv = "Title,Artist\n\"Song A,Artist A\n"
        assertFalse(parser.canParse("x.csv", csv))
        try {
            parser.parse("x.csv", csv)
            org.junit.Assert.fail("ImportParseException attendue")
        } catch (e: ImportParseException) {
            assertTrue(e.reason.contains("CSV"))
        }
    }
}

class GenericJsonParserTest {
    private val parser = GenericJsonParser()

    @Test
    fun `parses an array of tracks with aliased keys`() {
        val content = ImportFixtures.text("tracks.json")
        assertTrue(parser.canParse("tracks.json", content))
        val playlist = parser.parse("tracks.json", content).single()

        assertEquals("tracks", playlist.name)
        assertEquals(ImportFormat.GENERIC_JSON, playlist.sourceFormat)
        assertEquals(
            listOf(
                track("Alors on danse", "Stromae", album = "Cheese", durationMs = 207_000, isrc = "BE-R10-9-01-1"),
                track("Papaoutai", "Stromae", "Autre Artiste", album = "Racine carrée", durationMs = 232_000),
                track("Formidable", "Stromae", album = "Racine carrée", durationMs = 214_000),
                track("Lien direct", youtubeId = "dQw4w9WgXcQ"),
            ),
            playlist.tracks,
        )
    }

    @Test
    fun `parses an object with name and tracks`() {
        val playlist = parser.parse("whatever.json", ImportFixtures.text("playlist_object.json")).single()
        assertEquals("Ma sélection", playlist.name)
        assertEquals(
            listOf(
                track("Alors on danse", "Stromae", durationMs = 207_000),
                track("Papaoutai", "Stromae", durationMs = 232_000),
            ),
            playlist.tracks,
        )
    }

    @Test
    fun `parses several playlists from an array or a playlists key`() {
        val json = """[{"name":"A","tracks":[{"title":"T1"}]},{"name":"B","songs":[{"title":"T2","artist":"X"}]}]"""
        val fromArray = parser.parse("x.json", json)
        assertEquals(listOf("A", "B"), fromArray.map { it.name })
        assertEquals(listOf(track("T1")), fromArray[0].tracks)
        assertEquals(listOf(track("T2", "X")), fromArray[1].tracks)

        val wrapped = parser.parse("x.json", """{"playlists":$json}""")
        assertEquals(listOf("A", "B"), wrapped.map { it.name })
    }

    @Test
    fun `unwraps track envelopes and numeric string durations`() {
        val json = """{"items":[{"track":{"name":"Song","artists":["A","B"],"duration_ms":"180000"}}]}"""
        assertEquals(
            listOf(track("Song", "A", "B", durationMs = 180_000)),
            parser.parse("Ma_liste.json", json).single().tracks,
        )
        assertEquals("Ma liste", parser.parse("Ma_liste.json", json).single().name)
    }

    @Test
    fun `canParse rejects unrelated JSON and non JSON`() {
        assertFalse(parser.canParse("x.json", """{"foo": 1}"""))
        assertFalse(parser.canParse("x.json", """[1, 2, 3]"""))
        assertFalse(parser.canParse("x.json", """[{"note":"x"}]"""))
        assertFalse(parser.canParse("x.json", "Title,Artist\nA,B\n"))
        assertFalse(parser.canParse("x.csv", ImportFixtures.text("tracks.json")))
        assertFalse(parser.canParse("x.json", "[{\"title\": "))
    }

    @Test
    fun `invalid JSON throws a clear error`() {
        try {
            parser.parse("x.json", "[{\"title\": ")
            org.junit.Assert.fail("ImportParseException attendue")
        } catch (e: ImportParseException) {
            assertTrue(e.reason.contains("JSON"))
        }
    }
}
