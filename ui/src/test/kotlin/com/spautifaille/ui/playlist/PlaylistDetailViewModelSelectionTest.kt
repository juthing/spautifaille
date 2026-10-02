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
import com.spautifaille.ui.common.UiText
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Nouveaux comportements de la page playlist : bouton lecture/pause/reprise, disponibilité hors ligne (cache),
 * appui long (sélection vs réorganisation) et actions groupées sur une sélection.
 */
class PlaylistDetailViewModelSelectionTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakePlaylistRepository()
    private val downloadList = MutableStateFlow<List<Download>>(emptyList())
    private val downloads = mockk<DownloadRepository>(relaxed = true) {
        every { observeDownloads() } returns downloadList
    }
    private val likedIds = MutableStateFlow<Set<String>>(emptySet())
    private val library = mockk<LibraryRepository>(relaxed = true) {
        every { observeLikedIds() } returns likedIds
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
    private val cachedIds = MutableStateFlow<Set<String>>(emptySet())
    private var availabilityFlow: Flow<Set<String>> = cachedIds
    private val offlineAvailability = object : OfflineAvailability {
        override fun observePlayableIds(): Flow<Set<String>> = availabilityFlow
    }
    private val messenger = UiMessenger()
    private val notificationPermission = mockk<NotificationPermissionRequester>(relaxed = true)
    private val tracks = (1..5).map { track(it) }

    private fun seeded(): PlaylistDetailViewModel {
        repository.seed(Playlist(PLAYLIST_ID, "Road trip", 5, null, false, 0, 0), tracks)
        return PlaylistDetailViewModel(
            SavedStateHandle(mapOf(PlaylistDetailViewModel.ARG_ID to PLAYLIST_ID)),
            repository, downloads, library, playback, notificationPermission, network, offlineAvailability, messenger,
        )
    }

    private fun PlaylistDetailViewModel.entryId(index: Int) = uiState.value.entries[index].entryId

    private fun download(track: Track) = Download(track, DownloadState.COMPLETED, 1f, 1, 1, "/f/${track.id}", null, 0)

    // region Bouton Lecture / Pause / Reprendre

    @Test
    fun `play button starts the playlist when it is not the current queue`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        playerState.value = PlayerState(currentTrack = tracks[0], isPlaying = true, queueSourceId = QueueSources.playlist(99L))

        assertEquals(PlaylistPlayAction.PLAY, vm.uiState.value.playAction)
        vm.onPlayButton()

        verify { playback.play(tracks, 0, false, SOURCE) }
        verify(exactly = 0) { playback.pause() }
    }

    @Test
    fun `play button pauses when this playlist is playing and resumes when paused`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)

        playerState.value = PlayerState(currentTrack = tracks[2], isPlaying = true, queueSourceId = SOURCE)
        assertEquals(PlaylistPlayAction.PAUSE, vm.uiState.value.playAction)
        vm.onPlayButton()
        verify(exactly = 1) { playback.pause() }

        playerState.value = PlayerState(currentTrack = tracks[2], isPlaying = false, queueSourceId = SOURCE)
        assertEquals(PlaylistPlayAction.RESUME, vm.uiState.value.playAction)
        vm.onPlayButton()
        verify(exactly = 1) { playback.play() }

        // Ni pause ni reprise ne relancent la playlist depuis le début.
        verify(exactly = 0) { playback.play(any(), any(), any(), any()) }
    }

    @Test
    fun `buffering after a play request still counts as playing`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)

        playerState.value = PlayerState(currentTrack = tracks[0], isBuffering = true, playWhenReady = true, queueSourceId = SOURCE)

        assertEquals(PlaylistPlayAction.PAUSE, vm.uiState.value.playAction)
    }

    @Test
    fun `the play action goes back to PLAY when another source takes over`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        playerState.value = PlayerState(currentTrack = tracks[0], isPlaying = true, queueSourceId = SOURCE)
        assertEquals(PlaylistPlayAction.PAUSE, vm.uiState.value.playAction)

        playerState.value = PlayerState(currentTrack = tracks[0], isPlaying = true, queueSourceId = null)

        assertEquals(PlaylistPlayAction.PLAY, vm.uiState.value.playAction)
    }

    @Test
    fun `shuffle always restarts the playlist shuffled, even while it is playing`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        playerState.value = PlayerState(currentTrack = tracks[0], isPlaying = true, queueSourceId = SOURCE)

        vm.playAll(shuffle = true)

        verify { playback.play(tracks, 0, true, SOURCE) }
    }

    // endregion

    // region Hors ligne : cache de streaming

    @Test
    fun `offline, cached tracks stay playable and every track is still listed`() = runTest {
        downloadList.value = listOf(download(tracks[0]))
        cachedIds.value = setOf("video3")
        online.value = false
        val vm = seeded()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertEquals(5, state.entries.size)
        assertEquals(setOf("video1"), state.downloadedIds)
        assertEquals(setOf("video1", "video3"), state.playableOfflineIds)
        assertEquals(listOf("video1", "video3"), state.availableEntries.map { it.track.id })
        assertFalse(state.isAvailable(state.entries[1]))
        assertTrue(state.isAvailable(state.entries[2]))
    }

    @Test
    fun `offline play queue contains downloaded and cached tracks only`() = runTest {
        downloadList.value = listOf(download(tracks[0]))
        cachedIds.value = setOf("video4")
        online.value = false
        val vm = seeded()
        collectInBackground(vm.uiState)

        vm.playFrom(3)

        verify { playback.play(listOf(tracks[0], tracks[3]), 1, false, SOURCE) }
    }

    @Test
    fun `a track becoming cached while offline becomes playable`() = runTest {
        online.value = false
        val vm = seeded()
        collectInBackground(vm.uiState)
        assertFalse(vm.uiState.value.canPlay)

        cachedIds.value = setOf("video2")

        assertTrue(vm.uiState.value.canPlay)
    }

    @Test
    fun `the list is shown even when the offline index never answers or fails`() = runTest {
        availabilityFlow = MutableSharedFlow() // ne publie jamais
        val vm = seeded()
        collectInBackground(vm.uiState)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(5, vm.uiState.value.entries.size)

        availabilityFlow = flow { error("index illisible") }
        val vm2 = seeded()
        collectInBackground(vm2.uiState)
        assertFalse(vm2.uiState.value.isLoading)
        assertEquals(5, vm2.uiState.value.entries.size)
    }

    // endregion

    // region Appui long : sélection ou réorganisation

    @Test
    fun `long press released without moving enters selection with that row`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)

        vm.onDragStart(vm.entryId(2))
        vm.onDragEnd()

        assertEquals(setOf(vm.entryId(2)), vm.uiState.value.selectedEntryIds)
        assertTrue(vm.uiState.value.isSelecting)
        assertTrue(repository.moves.isEmpty())
    }

    @Test
    fun `long press followed by a move reorders and does not select`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)

        vm.onDragStart(vm.entryId(0))
        vm.onMove(0, 2)
        vm.onDragEnd()

        assertFalse(vm.uiState.value.isSelecting)
        assertEquals(listOf(Triple(PLAYLIST_ID, 0, 2)), repository.moves)
    }

    @Test
    fun `a long press after a completed drag still selects`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        vm.onDragStart(vm.entryId(0))
        vm.onMove(0, 1)
        vm.onDragEnd()

        vm.onDragStart(vm.entryId(3))
        vm.onDragEnd()

        assertEquals(setOf(vm.entryId(3)), vm.uiState.value.selectedEntryIds)
    }

    // endregion

    // region Sélection

    @Test
    fun `toggle selects, deselects and leaves selection mode with the last row`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        val a = vm.entryId(0)
        val b = vm.entryId(1)

        vm.startSelection(a)
        vm.toggleSelection(b)
        assertEquals(setOf(a, b), vm.uiState.value.selectedEntryIds)
        assertEquals(listOf("video1", "video2"), vm.uiState.value.selectedTracks.map { it.id })

        vm.toggleSelection(a)
        assertEquals(setOf(b), vm.uiState.value.selectedEntryIds)

        vm.toggleSelection(b)
        assertFalse(vm.uiState.value.isSelecting)
    }

    @Test
    fun `select all selects every row, a second call clears`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        vm.startSelection(vm.entryId(1))

        vm.toggleSelectAll()
        assertEquals(5, vm.uiState.value.selectedEntryIds.size)
        assertTrue(vm.uiState.value.allSelected)

        vm.toggleSelectAll()
        assertFalse(vm.uiState.value.isSelecting)
    }

    @Test
    fun `clearSelection and unknown rows`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)

        vm.toggleSelection(9_999L)
        vm.startSelection(9_999L)
        assertFalse(vm.uiState.value.isSelecting)

        vm.startSelection(vm.entryId(0))
        vm.clearSelection()
        assertFalse(vm.uiState.value.isSelecting)
    }

    @Test
    fun `a row removed elsewhere drops out of the selection`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        val removed = vm.uiState.value.entries[1]
        vm.startSelection(removed.entryId)
        vm.toggleSelection(vm.entryId(2))

        repository.removeEntry(PLAYLIST_ID, removed.entryId)

        assertEquals(setOf(vm.entryId(1)), vm.uiState.value.selectedEntryIds) // l'ancien index 2 est maintenant 1
        assertEquals(1, vm.uiState.value.selectedEntryIds.size)
    }

    // endregion

    // region Actions groupées

    private fun select(vm: PlaylistDetailViewModel, vararg indices: Int) {
        indices.forEach { vm.toggleSelection(vm.entryId(it)) }
    }

    @Test
    fun `playSelection replaces the queue with the selection in list order and clears it`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 3, 1)

        vm.playSelection()

        verify { playback.play(listOf(tracks[1], tracks[3])) }
        assertFalse(vm.uiState.value.isSelecting)
    }

    @Test
    fun `playSelectionNext and addSelectionToQueue call the controller, notify and clear`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)

        messenger.messages.test {
            select(vm, 0, 2)
            vm.playSelectionNext()
            verify { playback.playNext(listOf(tracks[0], tracks[2])) }
            assertFalse(vm.uiState.value.isSelecting)
            assertTrue(awaitItem() is UiText.Resource)

            select(vm, 4)
            vm.addSelectionToQueue()
            verify { playback.addToQueue(listOf(tracks[4])) }
            assertFalse(vm.uiState.value.isSelecting)
            assertTrue(awaitItem() is UiText.Resource)
        }
    }

    @Test
    fun `offline, queue actions only take playable tracks`() = runTest {
        downloadList.value = listOf(download(tracks[1]))
        online.value = false
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 0, 1)

        assertTrue(vm.uiState.value.canPlaySelection)
        vm.addSelectionToQueue()

        verify { playback.addToQueue(listOf(tracks[1])) }
    }

    @Test
    fun `offline with only unplayable tracks selected does nothing`() = runTest {
        online.value = false
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 0, 1)

        assertFalse(vm.uiState.value.canPlaySelection)
        vm.playSelection()
        vm.playSelectionNext()
        vm.addSelectionToQueue()

        verify(exactly = 0) { playback.play(any(), any(), any(), any()) }
        verify(exactly = 0) { playback.playNext(any()) }
        verify(exactly = 0) { playback.addToQueue(any()) }
        assertTrue(vm.uiState.value.isSelecting)
    }

    @Test
    fun `downloadSelection enqueues the not yet downloaded tracks, notifies and clears`() = runTest {
        downloadList.value = listOf(download(tracks[0]))
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 0, 1, 2)

        vm.events.test {
            vm.downloadSelection()
            assertEquals(PlaylistDetailEvent.DownloadsQueued(2), awaitItem())
        }
        coVerify { downloads.enqueue(listOf(tracks[1], tracks[2])) }
        verify(exactly = 1) { notificationPermission.requestIfNeeded() }
        assertFalse(vm.uiState.value.isSelecting)
    }

    @Test
    fun `downloadSelection does nothing offline`() = runTest {
        online.value = false
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 0)

        assertFalse(vm.uiState.value.canDownloadSelection)
        vm.downloadSelection()

        coVerify(exactly = 0) { downloads.enqueue(any()) }
    }

    @Test
    fun `likeSelection likes every selected track then unlikes when all are already liked`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 0, 1)

        vm.events.test {
            vm.likeSelection()
            assertEquals(PlaylistDetailEvent.TracksLiked(2, liked = true), awaitItem())
        }
        coVerify { library.setLiked(tracks[0], true) }
        coVerify { library.setLiked(tracks[1], true) }
        assertFalse(vm.uiState.value.isSelecting)

        likedIds.value = setOf("video1", "video2")
        select(vm, 0, 1)
        assertTrue(vm.uiState.value.allSelectedLiked)
        vm.events.test {
            vm.likeSelection()
            assertEquals(PlaylistDetailEvent.TracksLiked(2, liked = false), awaitItem())
        }
        coVerify { library.setLiked(tracks[0], false) }
        coVerify { library.setLiked(tracks[1], false) }
    }

    @Test
    fun `removeSelection removes the rows, emits one undoable event and clears`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 3, 1)

        vm.events.test {
            vm.removeSelection()
            assertEquals(
                PlaylistDetailEvent.TracksRemoved(listOf(RemovedTrack(tracks[1], 1), RemovedTrack(tracks[3], 3))),
                awaitItem(),
            )
        }
        assertEquals(listOf("video1", "video3", "video5"), vm.uiState.value.entries.map { it.track.id })
        assertFalse(vm.uiState.value.isSelecting)
    }

    @Test
    fun `undoing a multiple removal restores the original order`() = runTest {
        val vm = seeded()
        collectInBackground(vm.uiState)
        select(vm, 0, 2, 3)
        val removed = vm.uiState.value.selectedEntries.mapIndexed { i, e -> RemovedTrack(e.track, listOf(0, 2, 3)[i]) }
        vm.removeSelection()
        assertEquals(listOf("video2", "video5"), vm.uiState.value.entries.map { it.track.id })

        vm.undoRemove(removed)

        assertEquals(listOf("video1", "video2", "video3", "video4", "video5"), vm.uiState.value.entries.map { it.track.id })
    }

    @Test
    fun `in the downloaded playlist removeSelection deletes the files without an undo event`() = runTest {
        downloadList.value = tracks.take(3).map { download(it) }
        val vm = PlaylistDetailViewModel(
            SavedStateHandle(mapOf(PlaylistDetailViewModel.ARG_ID to Playlist.DOWNLOADED_ID)),
            repository, downloads, library, playback, notificationPermission, network, offlineAvailability, messenger,
        )
        collectInBackground(vm.uiState)
        vm.toggleSelectAll()

        vm.removeSelection()

        coVerify { downloads.delete("video1") }
        coVerify { downloads.delete("video2") }
        coVerify { downloads.delete("video3") }
        assertFalse(vm.uiState.value.isSelecting)
    }

    // endregion

    private companion object {
        const val PLAYLIST_ID = 10L
        val SOURCE = QueueSources.playlist(PLAYLIST_ID)
    }
}
