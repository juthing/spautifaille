package com.spautifaille.ui.playlist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.library.FakePlaylistRepository
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import com.spautifaille.ui.library.track
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaylistDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakePlaylistRepository()
    private val downloads = mockk<DownloadRepository>(relaxed = true)
    private val playback = mockk<PlaybackController>(relaxed = true)
    private val notificationPermission = mockk<NotificationPermissionRequester>(relaxed = true)
    private val tracks = (1..4).map { track(it, durationMs = 60_000L * it) }

    private fun userPlaylist() = Playlist(PLAYLIST_ID, "Road trip", 4, "https://img/1.jpg", false, 0, 0)

    private fun viewModel(id: Long? = PLAYLIST_ID): PlaylistDetailViewModel {
        val args = if (id != null) mapOf(PlaylistDetailViewModel.ARG_ID to id) else emptyMap()
        return PlaylistDetailViewModel(SavedStateHandle(args), repository, downloads, playback, notificationPermission)
    }

    private fun ids(vm: PlaylistDetailViewModel) = vm.uiState.value.entries.map { it.track.id }

    @Test
    fun `loads playlist with entries, count and total duration`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Road trip", state.playlist?.name)
        assertEquals(listOf("video1", "video2", "video3", "video4"), ids(vm))
        assertEquals(60_000L * (1 + 2 + 3 + 4), state.totalDurationMs)
        assertFalse(state.isSystem)
    }

    @Test
    fun `unknown playlist is reported as not found`() = runTest {
        val vm = viewModel(id = 999L)
        collectInBackground(vm.uiState)
        assertTrue(vm.uiState.value.isNotFound)
    }

    @Test
    fun `missing id argument is reported as not found`() = runTest {
        val vm = viewModel(id = null)
        collectInBackground(vm.uiState)
        assertTrue(vm.uiState.value.isNotFound)
    }

    @Test
    fun `playAll plays in order and shuffle plays shuffled`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playAll(shuffle = false)
        vm.playAll(shuffle = true)

        verify { playback.play(tracks, 0, false) }
        verify { playback.play(tracks, 0, true) }
    }

    @Test
    fun `playFrom starts at the tapped index`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playFrom(2)

        verify { playback.play(tracks, 2, false) }
    }

    @Test
    fun `play does nothing on an empty playlist`() = runTest {
        repository.seed(userPlaylist().copy(trackCount = 0))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playAll(true)
        vm.playFrom(0)

        verify(exactly = 0) { playback.play(any(), any(), any()) }
    }

    @Test
    fun `downloadAll enqueues every track and notifies`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.events.test {
            vm.downloadAll()
            assertEquals(PlaylistDetailEvent.DownloadsQueued(4), awaitItem())
        }
        coVerify { downloads.enqueue(tracks) }
        verify(exactly = 1) { notificationPermission.requestIfNeeded() }
    }

    @Test
    fun `playNext and addToQueue are forwarded to the controller`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()

        vm.playNext(tracks[1])
        vm.addToQueue(tracks[2])

        verify { playback.playNext(listOf(tracks[1])) }
        verify { playback.addToQueue(listOf(tracks[2])) }
    }

    @Test
    fun `drag reorders optimistically then commits moveEntry on drop`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onMove(0, 1)
        vm.onMove(1, 2)

        // Optimiste : l'UI est réordonnée, le repository n'a encore rien reçu.
        assertEquals(listOf("video2", "video3", "video1", "video4"), ids(vm))
        assertTrue(repository.moves.isEmpty())

        vm.onDragEnd()

        assertEquals(listOf(Triple(PLAYLIST_ID, 0, 2)), repository.moves)
        assertEquals(listOf("video2", "video3", "video1", "video4"), repository.tracksOf(PLAYLIST_ID).map { it.id })
        assertEquals(listOf("video2", "video3", "video1", "video4"), ids(vm))
    }

    @Test
    fun `dropping at the original position does not touch the repository`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onMove(0, 1)
        vm.onMove(1, 0)
        vm.onDragEnd()

        assertTrue(repository.moves.isEmpty())
        assertEquals(listOf("video1", "video2", "video3", "video4"), ids(vm))
    }

    @Test
    fun `failed move rolls the optimistic order back`() = runTest {
        repository.seed(userPlaylist(), tracks)
        repository.failMoves = true
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onMove(0, 3)
        vm.onDragEnd()

        assertEquals(listOf("video1", "video2", "video3", "video4"), ids(vm))
    }

    @Test
    fun `out of range moves are ignored`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onMove(-1, 2)
        vm.onMove(1, 9)
        vm.onDragEnd()

        assertTrue(repository.moves.isEmpty())
        assertEquals(listOf("video1", "video2", "video3", "video4"), ids(vm))
    }

    @Test
    fun `two consecutive drags commit two moves`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onMove(0, 1)
        vm.onDragEnd()
        vm.onMove(1, 0)
        vm.onDragEnd()

        assertEquals(listOf(Triple(PLAYLIST_ID, 0, 1), Triple(PLAYLIST_ID, 1, 0)), repository.moves)
        assertEquals(listOf("video1", "video2", "video3", "video4"), ids(vm))
    }

    @Test
    fun `removeEntry removes the track and emits an undoable event`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        val entry = vm.uiState.value.entries[1]

        vm.events.test {
            vm.removeEntry(entry)
            assertEquals(PlaylistDetailEvent.TrackRemoved(tracks[1], position = 1), awaitItem())
        }
        assertEquals(listOf("video1", "video3", "video4"), ids(vm))
    }

    @Test
    fun `undoRemove re-inserts the track at its original position`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.removeEntry(vm.uiState.value.entries[1])

        vm.undoRemove(tracks[1], position = 1)

        assertEquals(listOf("video1", "video2", "video3", "video4"), ids(vm))
        assertEquals(listOf(Triple(PLAYLIST_ID, 3, 1)), repository.moves)
    }

    @Test
    fun `undoRemove of the last track needs no move`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.removeEntry(vm.uiState.value.entries[3])

        vm.undoRemove(tracks[3], position = 3)

        assertEquals(listOf("video1", "video2", "video3", "video4"), ids(vm))
        assertTrue(repository.moves.isEmpty())
    }

    @Test
    fun `rename trims and validates the name`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertFalse(vm.rename("   "))
        assertEquals("Road trip", repository.playlist(PLAYLIST_ID)?.name)
        assertTrue(vm.rename("  Vacances "))
        assertEquals("Vacances", repository.playlist(PLAYLIST_ID)?.name)
    }

    @Test
    fun `delete removes a user playlist and emits PlaylistDeleted`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.events.test {
            assertTrue(vm.delete())
            assertEquals(PlaylistDetailEvent.PlaylistDeleted, awaitItem())
        }
        assertNull(repository.playlist(PLAYLIST_ID))
    }

    @Test
    fun `system playlist cannot be renamed or deleted`() = runTest {
        val vm = viewModel(id = Playlist.LIKED_ID)
        collectInBackground(vm.uiState)

        assertTrue(vm.uiState.value.isSystem)
        assertFalse(vm.rename("Autre"))
        assertFalse(vm.delete())
        assertEquals("Titres likés", repository.playlist(Playlist.LIKED_ID)?.name)
    }

    private companion object {
        const val PLAYLIST_ID = 10L
    }
}
