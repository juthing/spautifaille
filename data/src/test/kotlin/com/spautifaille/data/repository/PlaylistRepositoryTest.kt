package com.spautifaille.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.spautifaille.data.local.LIKED_PLAYLIST_NAME
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.track
import com.spautifaille.domain.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class PlaylistRepositoryTest {

    private lateinit var db: SpautifailleDatabase
    private lateinit var repo: PlaylistRepositoryImpl
    private var now = 1_000L

    @Before
    fun setUp() {
        db = createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        repo = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao()) { ++now }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun ids(playlistId: Long) =
        repo.observePlaylist(playlistId).first()!!.entries.map { it.track.id }

    private suspend fun positions(playlistId: Long) =
        repo.observePlaylist(playlistId).first()!!.entries.map { it.position }

    @Test
    fun likedPlaylistExistsAfterCreation() = runTest {
        val playlists = repo.observePlaylists().first()
        val liked = playlists.single { it.id == Playlist.LIKED_ID }
        assertEquals(LIKED_PLAYLIST_NAME, liked.name)
        assertTrue(liked.isSystem)
        assertEquals(0, liked.trackCount)
    }

    @Test
    fun createPlaylistWithTracksStoresEntriesAndTrackMetadata() = runTest {
        val id = repo.create("  Road trip ", listOf(track("a"), track("b"), track("c")))

        val playlist = repo.observePlaylist(id).first()
        assertNotNull(playlist)
        playlist!!
        assertEquals("Road trip", playlist.playlist.name)
        assertFalse(playlist.playlist.isSystem)
        assertEquals(3, playlist.playlist.trackCount)
        assertEquals(listOf("a", "b", "c"), playlist.entries.map { it.track.id })
        assertEquals(listOf(0, 1, 2), playlist.entries.map { it.position })
        assertEquals("Titre a", playlist.entries.first().track.title)
        // Pochette de la playlist = pochette du premier titre.
        assertEquals("https://img/a.jpg", playlist.playlist.thumbnailUrl)
    }

    @Test
    fun observePlaylistsListsLikedFirstWithTrackCount() = runTest {
        val id = repo.create("Ma playlist", listOf(track("a"), track("b")))

        repo.observePlaylists().test {
            val list = awaitItem()
            assertEquals(listOf(Playlist.LIKED_ID, id), list.map { it.id })
            assertEquals(2, list[1].trackCount)

            repo.addTracks(id, listOf(track("c")))
            assertEquals(3, awaitItem()[1].trackCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun renameUpdatesNameForUserPlaylist() = runTest {
        val id = repo.create("Avant")
        repo.rename(id, " Après ")
        assertEquals("Après", repo.observePlaylist(id).first()!!.playlist.name)
    }

    @Test
    fun likedPlaylistCannotBeRenamedOrDeleted() = runTest {
        repo.rename(Playlist.LIKED_ID, "Autre nom")
        repo.delete(Playlist.LIKED_ID)

        val liked = repo.observePlaylist(Playlist.LIKED_ID).first()
        assertNotNull(liked)
        liked!!
        assertEquals(LIKED_PLAYLIST_NAME, liked.playlist.name)
    }

    @Test
    fun deletePlaylistCascadesToEntriesButKeepsTracks() = runTest {
        val id = repo.create("Temp", listOf(track("a")))
        repo.delete(id)

        assertNull(repo.observePlaylist(id).first())
        assertEquals(0, db.query("SELECT * FROM playlist_entries", null).use { it.count })
        assertNotNull(db.trackDao().get("a"))
    }

    @Test
    fun addTracksAppendsAtEndAndAllowsDuplicates() = runTest {
        val id = repo.create("P", listOf(track("a"), track("b")))
        repo.addTracks(id, listOf(track("c"), track("a")))

        assertEquals(listOf("a", "b", "c", "a"), ids(id))
        assertEquals(listOf(0, 1, 2, 3), positions(id))
        assertTrue(repo.containsTrack(id, "c"))
        assertFalse(repo.containsTrack(id, "zzz"))
    }

    @Test
    fun removeEntryKeepsPositionsContiguous() = runTest {
        val id = repo.create("P", listOf(track("a"), track("b"), track("c"), track("d")))
        val entries = repo.observePlaylist(id).first()!!.entries

        repo.removeEntry(id, entries[1].entryId)

        assertEquals(listOf("a", "c", "d"), ids(id))
        assertEquals(listOf(0, 1, 2), positions(id))
    }

    @Test
    fun removeEntryRemovesOnlyThatOccurrence() = runTest {
        val id = repo.create("P", listOf(track("a"), track("b"), track("a")))
        val entries = repo.observePlaylist(id).first()!!.entries

        repo.removeEntry(id, entries[2].entryId)

        assertEquals(listOf("a", "b"), ids(id))
        assertTrue(repo.containsTrack(id, "a"))
    }

    @Test
    fun moveEntryDownAndUpShiftsNeighbours() = runTest {
        val id = repo.create("P", listOf(track("a"), track("b"), track("c"), track("d"), track("e")))

        repo.moveEntry(id, fromPosition = 0, toPosition = 3)
        assertEquals(listOf("b", "c", "d", "a", "e"), ids(id))
        assertEquals(listOf(0, 1, 2, 3, 4), positions(id))

        repo.moveEntry(id, fromPosition = 4, toPosition = 1)
        assertEquals(listOf("b", "e", "c", "d", "a"), ids(id))
        assertEquals(listOf(0, 1, 2, 3, 4), positions(id))
    }

    @Test
    fun moveEntryClampsAndIgnoresInvalidPositions() = runTest {
        val id = repo.create("P", listOf(track("a"), track("b"), track("c")))

        repo.moveEntry(id, 0, 99)
        assertEquals(listOf("b", "c", "a"), ids(id))

        repo.moveEntry(id, 7, 0) // source hors bornes : sans effet
        repo.moveEntry(id, 1, 1)
        assertEquals(listOf("b", "c", "a"), ids(id))
        assertEquals(listOf(0, 1, 2), positions(id))
    }

    @Test
    fun createWithBlankNameIsRejected() = runTest {
        val result = runCatching { repo.create("   ") }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun observePlaylistEmitsNullForUnknownId() = runTest {
        assertNull(repo.observePlaylist(9_999).first())
    }
}
