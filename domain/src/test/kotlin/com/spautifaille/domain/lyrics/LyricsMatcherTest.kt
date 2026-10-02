package com.spautifaille.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatcherTest {

    private val query = LyricsQuery(
        trackName = "Blinding Lights",
        artistName = "The Weeknd",
        primaryArtist = "The Weeknd",
        albumName = null,
        durationSec = 200,
    )

    private val lrc = "[00:10.00]Yeah\n[00:20.00]I've been tryna call"

    private fun candidate(
        duration: Double? = 200.0,
        synced: String? = lrc,
        plain: String? = "Yeah\nI've been tryna call",
        title: String = "Blinding Lights",
        artist: String = "The Weeknd",
        instrumental: Boolean = false,
    ) = LyricsCandidate(title, artist, duration, instrumental, synced, plain)

    @Test
    fun `synchronisees preferees aux brutes a duree equivalente`() {
        val result = LyricsMatcher.select(query, listOf(candidate(synced = null), candidate(duration = 201.0)))
        assertTrue(result is Lyrics.Synced)
    }

    @Test
    fun `duree la plus proche parmi les synchronisees`() {
        val far = candidate(duration = 207.0, synced = "[00:01.00]loin")
        val near = candidate(duration = 201.0, synced = "[00:01.00]proche")
        val result = LyricsMatcher.select(query, listOf(far, near)) as Lyrics.Synced
        assertEquals("proche", result.lines.single().text)
    }

    @Test
    fun `une brute a duree exacte bat une synchronisee eloignee`() {
        val syncedFar = candidate(duration = 208.0)
        val plainClose = candidate(duration = 200.0, synced = null, plain = "texte")
        val result = LyricsMatcher.select(query, listOf(syncedFar, plainClose))
        assertEquals(Lyrics.Plain("texte"), result)
    }

    @Test
    fun `synchronisee trop eloignee en duree ecartee mais brute acceptee`() {
        val c = candidate(duration = 230.0)
        assertTrue(LyricsMatcher.select(query, listOf(c)) is Lyrics.Plain)
        assertNull(LyricsMatcher.select(query, listOf(c.copy(plainLyrics = null))))
    }

    @Test
    fun `brute tres eloignee ecartee`() {
        assertNull(LyricsMatcher.select(query, listOf(candidate(duration = 400.0, synced = null))))
    }

    @Test
    fun `duree inconnue acceptee`() {
        assertTrue(LyricsMatcher.select(query, listOf(candidate(duration = null))) is Lyrics.Synced)
        assertTrue(LyricsMatcher.select(query.copy(durationSec = null), listOf(candidate())) is Lyrics.Synced)
    }

    @Test
    fun `fiche vide ou lrc sans texte ignoree`() {
        assertNull(LyricsMatcher.select(query, listOf(candidate(synced = null, plain = null))))
        val emptyLrc = candidate(synced = "[ar:x]\n[00:01.00]", plain = null)
        assertNull(LyricsMatcher.select(query, listOf(emptyLrc)))
    }

    @Test
    fun `lrc inutilisable mais brute presente donne la brute`() {
        val c = candidate(synced = "n'importe quoi", plain = "texte")
        assertEquals(Lyrics.Plain("texte"), LyricsMatcher.select(query, listOf(c)))
    }

    @Test
    fun `instrumental choisi seulement faute de paroles`() {
        val instrumental = candidate(synced = null, plain = null, instrumental = true)
        assertEquals(Lyrics.Instrumental(), LyricsMatcher.select(query, listOf(instrumental)))
        val withLyrics = candidate(duration = 203.0)
        assertTrue(LyricsMatcher.select(query, listOf(instrumental, withLyrics)) is Lyrics.Synced)
    }

    @Test
    fun `titre ou artiste sans rapport ecartes`() {
        assertNull(LyricsMatcher.select(query, listOf(candidate(title = "Completely Different Song"))))
        assertNull(LyricsMatcher.select(query, listOf(candidate(artist = "Quelqu'un D'autre"))))
    }

    @Test
    fun `variantes de titre et d artiste acceptees`() {
        assertTrue(LyricsMatcher.select(query, listOf(candidate(title = "The Weeknd - Blinding Lights"))) != null)
        assertTrue(LyricsMatcher.select(query, listOf(candidate(artist = "TheWeeknd"))) != null)
        assertTrue(LyricsMatcher.select(query, listOf(candidate(artist = "The Weeknd, Autre"))) != null)
        assertTrue(LyricsMatcher.select(query, listOf(candidate(title = "blinding lights"))) != null)
    }

    @Test
    fun `aucun candidat`() {
        assertNull(LyricsMatcher.select(query, emptyList()))
    }
}
