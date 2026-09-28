package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpotifyJsonParserTest {
    private val parser = SpotifyJsonParser()

    @Test
    fun `parses Playlist1 json into one playlist per playlist`() {
        val playlists = parser.parse("Playlist1.json", ImportFixtures.text("Playlist1.json"))

        assertEquals(listOf("Road trip 🚗", "Sélection 夜"), playlists.map { it.name })
        assertTrue(playlists.all { it.sourceFormat == ImportFormat.SPOTIFY_JSON })
        assertEquals(
            listOf(
                track("Bohemian Rhapsody - Remastered 2011", "Queen", album = "A Night at the Opera (Deluxe Remastered Version)"),
                track("Tyler, The Creator Interlude", "Tyler, The Creator", album = "IGOR"),
                // titre local : nom déduit de spotify:local:artiste:album:titre:durée
                track("Chanson maison", "Mon Groupe", album = "Demo 2019", durationMs = 187_000),
            ),
            playlists[0].tracks,
        )
        assertEquals(listOf(track("夜に駆ける", "YOASOBI", album = "THE BOOK")), playlists[1].tracks)
    }

    @Test
    fun `skips episodes and nameless tracks`() {
        val tracks = parser.parse("Playlist1.json", ImportFixtures.text("Playlist1.json")).flatMap { it.tracks }
        assertFalse(tracks.any { it.title.contains("Épisode") })
        assertFalse(tracks.any { it.artists.contains("Sans nom") })
        assertEquals(4, tracks.size)
    }

    @Test
    fun `parses YourLibrary as the liked tracks playlist`() {
        val playlists = parser.parse("YourLibrary.json", ImportFixtures.text("YourLibrary.json"))
        val liked = playlists.single()
        assertEquals("Titres likés (Spotify)", liked.name)
        assertEquals(SpotifyJsonParser.LIKED_PLAYLIST_NAME, liked.name)
        assertEquals(
            listOf(
                track("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk", album = "Random Access Memories"),
                track("Balance ton quoi", "Angèle", album = "Brol"),
            ),
            liked.tracks,
        )
    }

    @Test
    fun `handles both playlists and tracks in one document`() {
        val json = """{"playlists":[{"name":"P","items":[{"track":{"trackName":"A","artistName":"B"}}]}],
            |"tracks":[{"artist":"C","album":"D","track":"E","uri":"spotify:track:1"}]}""".trimMargin()
        val playlists = parser.parse(null, json)
        assertEquals(listOf("P", "Titres likés (Spotify)"), playlists.map { it.name })
    }

    @Test
    fun `is tolerant to missing fields extra fields and nulls`() {
        val json = """{"playlists":[
            |{"items":[{"track":{"trackName":"Only title"}},{"track":{"trackName":"T","artistName":null,"albumName":null,"extra":{"x":1}}},{}, 42]},
            |{"name":"Vide","items":[]},
            |"pas un objet"
            |],"unknownRoot":true}""".trimMargin()
        val playlists = parser.parse(null, json)
        assertEquals(listOf("Playlist Spotify 1", "Vide"), playlists.map { it.name })
        assertEquals(listOf(track("Only title"), track("T")), playlists[0].tracks)
        assertTrue(playlists[1].tracks.isEmpty())
    }

    @Test
    fun `local track with explicit names is kept`() {
        val json = """{"playlists":[{"name":"L","items":[{"track":null,"localTrack":{"trackName":"Démo","artistName":"Moi","albumName":"Cave"}}]}]}"""
        assertEquals(listOf(track("Démo", "Moi", album = "Cave")), parser.parse("p.json", json).single().tracks)
    }

    @Test
    fun `canParse recognises Spotify documents only`() {
        assertTrue(parser.canParse("Playlist1.json", ImportFixtures.text("Playlist1.json")))
        assertTrue(parser.canParse("YourLibrary.json", ImportFixtures.text("YourLibrary.json")))
        assertTrue(parser.canParse(null, ImportFixtures.text("Playlist1.json")))
        assertFalse(parser.canParse("tracks.json", ImportFixtures.text("tracks.json")))
        assertFalse(parser.canParse("playlist_object.json", ImportFixtures.text("playlist_object.json")))
        assertFalse(parser.canParse("x.csv", ImportFixtures.text("Playlist1.json")))
        assertFalse(parser.canParse("x.json", "{ not json"))
        assertFalse(parser.canParse("x.json", ImportFixtures.text("exportify_current.csv")))
        assertFalse(parser.canParse("x.json", """{"foo":1}"""))
    }

    @Test
    fun `malformed JSON throws ImportParseException`() {
        try {
            parser.parse("x.json", """{"playlists": [ {""")
            fail("ImportParseException attendue")
        } catch (e: ImportParseException) {
            assertTrue(e.reason.contains("JSON"))
        }
    }

    @Test
    fun `UTF-8 BOM in front of JSON is accepted`() {
        val json = "﻿" + ImportFixtures.text("YourLibrary.json")
        assertTrue(parser.canParse("YourLibrary.json", json))
        assertEquals(2, parser.parse("YourLibrary.json", json).single().tracks.size)
    }
}
