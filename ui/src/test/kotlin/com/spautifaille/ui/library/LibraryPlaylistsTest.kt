package com.spautifaille.ui.library

import com.spautifaille.domain.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlaylistsTest {

    private val liked = Playlist(Playlist.LIKED_ID, "Titres likés", 3, null, true, 0, 0)
    private val road = Playlist(10, "Road trip", 5, null, false, 0, 0)
    private val focus = Playlist(11, "Focus", 2, null, false, 0, 0)

    @Test
    fun `online order is liked, downloaded then user playlists in repository order`() {
        val result = orderLibraryPlaylists(listOf(liked, road, focus), downloadedCount = 4, isOffline = false)
        assertEquals(listOf(Playlist.LIKED_ID, Playlist.DOWNLOADED_ID, 10L, 11L), result.map { it.id })
        assertEquals(4, result[1].trackCount)
    }

    @Test
    fun `offline order puts downloaded before liked`() {
        val result = orderLibraryPlaylists(listOf(liked, road), downloadedCount = 0, isOffline = true)
        assertEquals(listOf(Playlist.DOWNLOADED_ID, Playlist.LIKED_ID, 10L), result.map { it.id })
    }

    @Test
    fun `downloaded playlist is present even when empty and without stored playlists`() {
        val result = orderLibraryPlaylists(emptyList(), downloadedCount = 0, isOffline = false)
        assertEquals(listOf(Playlist.DOWNLOADED_ID), result.map { it.id })
    }

    @Test
    fun `pinned detection covers liked, downloaded and system playlists only`() {
        assertTrue(liked.isPinned)
        assertTrue(orderLibraryPlaylists(emptyList(), 0, false).single().isPinned)
        assertFalse(road.isPinned)
    }
}
