package com.spautifaille.ui.playlist

import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.ui.library.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAvailabilityTest {

    private val tracks = (1..4).map { track(it) }
    private val entries = tracks.mapIndexed { i, t -> PlaylistEntry(i + 1L, i, t) }
    private val downloaded = setOf("video2", "video4")

    @Test
    fun `online everything is available`() {
        assertTrue(isTrackAvailable("video1", downloaded, isOffline = false))
        assertEquals(entries, entries.availableEntries(downloaded, isOffline = false))
        assertEquals(tracks, tracks.availableTracks(emptySet(), isOffline = false))
    }

    @Test
    fun `offline only downloaded tracks are available and order is kept`() {
        assertFalse(isTrackAvailable("video1", downloaded, isOffline = true))
        assertTrue(isTrackAvailable("video2", downloaded, isOffline = true))
        assertEquals(listOf(entries[1], entries[3]), entries.availableEntries(downloaded, isOffline = true))
        assertEquals(listOf(tracks[1], tracks[3]), tracks.availableTracks(downloaded, isOffline = true))
    }

    @Test
    fun `offline with no downloads leaves nothing playable`() {
        assertTrue(entries.availableEntries(emptySet(), isOffline = true).isEmpty())
    }

    @Test
    fun `completed downloads are filtered and sorted most recent first`() {
        fun download(i: Int, state: DownloadState, at: Long) = Download(tracks[i], state, 1f, 1, 1, "/f", null, at)
        val downloads = listOf(
            download(0, DownloadState.COMPLETED, at = 10),
            download(1, DownloadState.QUEUED, at = 50),
            download(2, DownloadState.COMPLETED, at = 30),
            download(3, DownloadState.PAUSED, at = 40),
        )

        assertEquals(listOf("video3", "video1"), downloads.completedDownloads().map { it.track.id })
        val asEntries = downloads.toPlaylistEntries()
        assertEquals(listOf(1L, 2L), asEntries.map { it.entryId })
        assertEquals(listOf(0, 1), asEntries.map { it.position })
        assertEquals(listOf("video3", "video1"), asEntries.map { it.track.id })
    }

    @Test
    fun `virtual downloaded playlist uses the reserved negative id`() {
        val playlist = downloadedPlaylist(trackCount = 7)
        assertEquals(Playlist.DOWNLOADED_ID, playlist.id)
        assertTrue(playlist.id < 0)
        assertTrue(playlist.isSystem)
        assertEquals(7, playlist.trackCount)
    }
}
