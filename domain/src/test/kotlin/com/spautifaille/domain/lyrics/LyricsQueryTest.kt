package com.spautifaille.domain.lyrics

import com.spautifaille.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsQueryTest {

    private fun query(title: String, artist: String, album: String? = null, durationMs: Long? = null) =
        LyricsQuery.from(Track(id = "x", title = title, artist = artist, album = album, durationMs = durationMs))

    @Test
    fun `suffixes de titre YouTube retires`() {
        assertEquals("Blinding Lights", query("Blinding Lights (Official Video)", "The Weeknd").trackName)
        assertEquals("Blinding Lights", query("Blinding Lights [Lyrics]", "The Weeknd").trackName)
        assertEquals("Blinding Lights", query("Blinding Lights (Official Audio) HD", "The Weeknd").trackName)
        assertEquals("Song", query("Song - Remastered 2011", "Band").trackName)
    }

    @Test
    fun `invites retires du titre`() {
        assertEquals("Titre", query("Titre (feat. Invité)", "Artiste").trackName)
        assertEquals("Titre", query("Titre ft. Invité", "Artiste").trackName)
    }

    @Test
    fun `prefixe artiste retire`() {
        assertEquals("Blinding Lights", query("The Weeknd - Blinding Lights (Official Video)", "The Weeknd").trackName)
        assertEquals("Blinding Lights", query("The Weeknd - Blinding Lights", "TheWeekndVEVO").trackName)
    }

    @Test
    fun `tiret appartenant au titre conserve`() {
        assertEquals("Wake Me Up - Acoustic", query("Wake Me Up - Acoustic", "Avicii").trackName)
        assertEquals("Hello - Goodbye", query("Hello - Goodbye", "The Beatles").trackName)
    }

    @Test
    fun `marqueurs de version conserves`() {
        assertEquals("Song (Live)", query("Song (Live)", "Band").trackName)
        assertEquals("Song (Remix)", query("Song (Remix) [Official Video]", "Band").trackName)
    }

    @Test
    fun `suffixes d artiste retires`() {
        assertEquals("Daft Punk", query("t", "Daft Punk - Topic").artistName)
        assertEquals("TheWeeknd", query("t", "TheWeekndVEVO").artistName)
        assertEquals("The Weeknd", query("t", "The Weeknd Official").artistName)
        assertEquals("Artiste", query("t", "Artiste feat. Autre").artistName)
    }

    @Test
    fun `artiste principal`() {
        val q = query("t", "Calvin Harris, Dua Lipa & Autre")
        assertEquals("Calvin Harris", q.primaryArtist)
        assertEquals("Calvin Harris, Dua Lipa & Autre", q.artistName)
        assertEquals("AC/DC", query("t", "AC/DC").primaryArtist)
    }

    @Test
    fun `duree en secondes arrondie et album vide ignore`() {
        assertEquals(200, query("t", "a", durationMs = 200_400).durationSec)
        assertEquals(201, query("t", "a", durationMs = 200_600).durationSec)
        assertNull(query("t", "a", durationMs = null).durationSec)
        assertNull(query("t", "a", durationMs = 0).durationSec)
        assertNull(query("t", "a", album = "  ").albumName)
        assertEquals("After Hours", query("t", "a", album = "After Hours").albumName)
    }

    @Test
    fun `titre uniquement decore garde le titre d origine`() {
        assertEquals("(Official Video)", query("(Official Video)", "a").trackName)
    }

    @Test
    fun `texte de recherche libre`() {
        assertEquals("The Weeknd Blinding Lights", query("Blinding Lights (Official Video)", "The Weeknd - Topic").searchText)
    }
}
