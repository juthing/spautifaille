package com.spautifaille.ui.playlist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.QueueSources
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.OfflineAvailability
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.library.FakePlaylistRepository
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import com.spautifaille.ui.library.track
import com.spautifaille.ui.network.NetworkMonitor
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val downloadList = MutableStateFlow<List<Download>>(emptyList())
    private val downloads = mockk<DownloadRepository>(relaxed = true) {
        every { observeDownloads() } returns downloadList
    }
    private val playerState = MutableStateFlow(PlayerState())
    private val playback = mockk<PlaybackController>(relaxed = true) {
        every { state } returns playerState
    }
    private val online = MutableStateFlow(true)
    private val network = object : NetworkMonitor {
        override val isUnmetered: Flow<Boolean> = online
        override val isOnline: Flow<Boolean> = online
    }
    private val notificationPermission = mockk<NotificationPermissionRequester>(relaxed = true)
    private val cachedIds = MutableStateFlow<Set<String>>(emptySet())
    private val offlineAvailability = object : OfflineAvailability {
        override fun observePlayableIds(): Flow<Set<String>> = cachedIds
    }
    private val likedIds = MutableStateFlow<Set<String>>(emptySet())
    private val library = mockk<LibraryRepository>(relaxed = true) {
        every { observeLikedIds() } returns likedIds
    }
    private val messenger = UiMessenger()
    private val tracks = (1..4).map { track(it, durationMs = 60_000L * it) }

    private fun userPlaylist() = Playlist(PLAYLIST_ID, "Road trip", 4, "https://img/1.jpg", false, 0, 0)

    private fun viewModel(id: Long? = PLAYLIST_ID): PlaylistDetailViewModel {
        val args = if (id != null) mapOf(PlaylistDetailViewModel.ARG_ID to id) else emptyMap()
        return PlaylistDetailViewModel(
            SavedStateHandle(args), repository, downloads, library, playback, notificationPermission, network,
            offlineAvailability, messenger,
        )
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

        verify { playback.play(tracks, 0, false, SOURCE) }
        verify { playback.play(tracks, 0, true, SOURCE) }
    }

    @Test
    fun `playFrom starts at the tapped index`() = runTest {
        repository.seed(userPlaylist(), tracks)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playFrom(2)

        verify { playback.play(tracks, 2, false, SOURCE) }
    }

    @Test
    fun `play does nothing on an empty playlist`() = runTest {
        repository.seed(userPlaylist().copy(trackCount = 0))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playAll(true)
        vm.playFrom(0)

        verify(exactly = 0) { playback.play(any(), any(), any(), any()) }
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
            assertEquals(PlaylistDetailEvent.TracksRemoved(listOf(RemovedTrack(tracks[1], position = 1))), awaitItem())
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

    private fun download(track: Track, state: DownloadState = DownloadState.COMPLETED, createdAt: Long = 0L) =
        Download(track, state, 1f, 10, 10, "/f/${track.id}", null, createdAt)

    @Test
    fun `exposes downloaded ids and current playing track`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = listOf(
            download(tracks[0]),
            download(tracks[1], DownloadState.RUNNING),
        )
        playerState.value = PlayerState(currentTrack = tracks[2], isPlaying = true)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertEquals(setOf("video1"), state.downloadedIds)
        assertEquals("video3", state.currentTrackId)
        assertTrue(state.isPlaying)
        assertFalse(state.isOffline)
    }

    @Test
    fun `online play uses every track even when only some are downloaded`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = listOf(download(tracks[0]))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playAll(shuffle = false)
        vm.playFrom(3)

        verify { playback.play(tracks, 0, false, SOURCE) }
        verify { playback.play(tracks, 3, false, SOURCE) }
    }

    @Test
    fun `offline playAll only plays downloaded tracks`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = listOf(download(tracks[1]), download(tracks[3]))
        online.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.uiState.value.isOffline)
        vm.playAll(shuffle = true)

        verify { playback.play(listOf(tracks[1], tracks[3]), 0, true, SOURCE) }
    }

    @Test
    fun `offline playFrom starts at the tapped track inside the available queue`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = listOf(download(tracks[1]), download(tracks[3]))
        online.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playFrom(3)

        verify { playback.play(listOf(tracks[1], tracks[3]), 1, false, SOURCE) }
    }

    @Test
    fun `offline tap on a track that is not downloaded is rejected`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = listOf(download(tracks[1]))
        online.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.events.test {
            vm.playFrom(0)
            assertEquals(PlaylistDetailEvent.TrackUnavailableOffline, awaitItem())
        }
        verify(exactly = 0) { playback.play(any(), any(), any(), any()) }
    }

    @Test
    fun `offline with nothing downloaded plays nothing and cannot play`() = runTest {
        repository.seed(userPlaylist(), tracks)
        online.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playAll(shuffle = false)

        assertFalse(vm.uiState.value.canPlay)
        verify(exactly = 0) { playback.play(any(), any(), any(), any()) }
    }

    @Test
    fun `network coming back makes every track playable again`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = listOf(download(tracks[0]))
        online.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertEquals(1, vm.uiState.value.availableEntries.size)

        online.value = true

        assertFalse(vm.uiState.value.isOffline)
        assertEquals(4, vm.uiState.value.availableEntries.size)
        assertTrue(vm.uiState.value.isAvailable(vm.uiState.value.entries[2]))
    }

    @Test
    fun `downloadAll skips tracks already downloaded and does nothing offline`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = listOf(download(tracks[0]), download(tracks[1]))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.events.test {
            vm.downloadAll()
            assertEquals(PlaylistDetailEvent.DownloadsQueued(2), awaitItem())
        }
        coVerify { downloads.enqueue(listOf(tracks[2], tracks[3])) }

        online.value = false
        vm.downloadAll()
        coVerify(exactly = 1) { downloads.enqueue(any()) }
    }

    @Test
    fun `fully downloaded playlist reports it and cannot be downloaded again`() = runTest {
        repository.seed(userPlaylist(), tracks)
        downloadList.value = tracks.map { download(it) }
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.uiState.value.isFullyDownloaded)
        assertFalse(vm.uiState.value.canDownload)
        vm.downloadAll()
        coVerify(exactly = 0) { downloads.enqueue(any()) }
    }

    @Test
    fun `downloaded playlist lists completed downloads, most recent first`() = runTest {
        downloadList.value = listOf(
            download(tracks[0], createdAt = 1),
            download(tracks[1], DownloadState.RUNNING, createdAt = 5),
            download(tracks[2], createdAt = 3),
            download(tracks[3], DownloadState.FAILED, createdAt = 9),
        )
        val vm = viewModel(id = Playlist.DOWNLOADED_ID)
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isNotFound)
        assertTrue(state.isDownloadedPlaylist)
        assertTrue(state.isSystem)
        assertEquals(Playlist.DOWNLOADED_ID, state.playlist?.id)
        assertEquals(2, state.playlist?.trackCount)
        assertEquals(listOf("video3", "video1"), ids(vm))
        assertEquals(60_000L * (3 + 1), state.totalDurationMs)
    }

    @Test
    fun `downloaded playlist is empty but found when nothing is downloaded`() = runTest {
        val vm = viewModel(id = Playlist.DOWNLOADED_ID)
        collectInBackground(vm.uiState)

        assertFalse(vm.uiState.value.isNotFound)
        assertTrue(vm.uiState.value.entries.isEmpty())
        assertFalse(vm.uiState.value.canPlay)
    }

    @Test
    fun `downloaded playlist plays and shuffles its tracks and hides download all`() = runTest {
        downloadList.value = listOf(download(tracks[0], createdAt = 2), download(tracks[1], createdAt = 1))
        val vm = viewModel(id = Playlist.DOWNLOADED_ID)
        collectInBackground(vm.uiState)

        vm.playAll(shuffle = true)
        vm.playFrom(1)
        vm.downloadAll()

        verify { playback.play(listOf(tracks[0], tracks[1]), 0, true, QueueSources.playlist(Playlist.DOWNLOADED_ID)) }
        verify { playback.play(listOf(tracks[0], tracks[1]), 1, false, QueueSources.playlist(Playlist.DOWNLOADED_ID)) }
        assertFalse(vm.uiState.value.canDownload)
        coVerify(exactly = 0) { downloads.enqueue(any()) }
    }

    @Test
    fun `downloaded playlist cannot be renamed or deleted and removing a track deletes its file`() = runTest {
        downloadList.value = listOf(download(tracks[0]))
        val vm = viewModel(id = Playlist.DOWNLOADED_ID)
        collectInBackground(vm.uiState)

        assertFalse(vm.rename("Autre"))
        assertFalse(vm.delete())

        vm.removeEntry(vm.uiState.value.entries.first())

        coVerify { downloads.delete("video1") }
        coVerify(exactly = 0) { downloads.enqueue(any()) }
    }

    private companion object {
        const val PLAYLIST_ID = 10L
        val SOURCE = QueueSources.playlist(PLAYLIST_ID)
    }
}
