package com.spautifaille.ui.library

import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.LibraryRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val playlists = FakePlaylistRepository()
    private val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val subscriptions = MutableStateFlow<List<Artist>>(emptyList())
    private val downloads = MutableStateFlow<List<Download>>(emptyList())
    private val storage = MutableStateFlow(StorageUsage(0, 0, 0))

    private val library = mockk<LibraryRepository>(relaxed = true) {
        every { observeHistory(any()) } returns history
        every { observeSubscriptions() } returns subscriptions
    }
    private val downloadRepository = mockk<DownloadRepository>(relaxed = true) {
        every { observeDownloads() } returns downloads
        every { observeStorageUsage() } returns storage
    }
    private val playback = mockk<PlaybackController>(relaxed = true)

    private fun viewModel() = LibraryViewModel(playlists, library, downloadRepository, playback)

    private fun userPlaylist(id: Long, name: String, count: Int = 0) =
        Playlist(id, name, count, null, false, 0, 0)

    private fun download(index: Int, state: DownloadState) =
        Download(track(index), state, 1f, 10, 10, "/f$index", null, index.toLong())

    @Test
    fun `state exposes system playlist first then user playlists`() = runTest {
        playlists.seed(userPlaylist(10, "Road trip", 2), listOf(track(1), track(2)))
        playlists.seed(userPlaylist(11, "Focus"))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf(Playlist.LIKED_ID, 10L, 11L), state.playlists.map { it.id })
        assertTrue(state.playlists.first().isSystem)
    }

    @Test
    fun `initial state is loading`() {
        assertTrue(viewModel().uiState.value.isLoading)
    }

    @Test
    fun `downloads tab keeps only completed downloads and exposes storage`() = runTest {
        downloads.value = listOf(
            download(1, DownloadState.COMPLETED),
            download(2, DownloadState.RUNNING),
            download(3, DownloadState.FAILED),
            download(4, DownloadState.COMPLETED),
        )
        storage.value = StorageUsage(downloadsBytes = 2048, cacheBytes = 0, downloadCount = 2)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertEquals(listOf("video1", "video4"), vm.uiState.value.downloads.map { it.track.id })
        assertEquals(2048L, vm.uiState.value.storage?.downloadsBytes)
    }

    @Test
    fun `createPlaylist trims the name`() = runTest {
        val vm = viewModel()
        assertTrue(vm.createPlaylist("  Ma playlist  "))
        assertEquals(listOf("Ma playlist"), playlists.createdNames)
    }

    @Test
    fun `createPlaylist rejects blank and too long names`() = runTest {
        val vm = viewModel()
        assertFalse(vm.createPlaylist(""))
        assertFalse(vm.createPlaylist("   \n "))
        assertFalse(vm.createPlaylist("x".repeat(PlaylistNameValidator.MAX_LENGTH + 1)))
        assertTrue(playlists.createdNames.isEmpty())
    }

    @Test
    fun `validator reports the right error`() {
        assertEquals(PlaylistNameError.BLANK, PlaylistNameValidator.validate("  "))
        assertEquals(PlaylistNameError.TOO_LONG, PlaylistNameValidator.validate("x".repeat(101)))
        assertNull(PlaylistNameValidator.validate(" ok "))
        assertNull(PlaylistNameValidator.validate("x".repeat(100)))
    }

    @Test
    fun `renamePlaylist applies a valid trimmed name`() = runTest {
        playlists.seed(userPlaylist(10, "Ancien"))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.renamePlaylist(10, "  Nouveau "))
        assertEquals("Nouveau", playlists.playlist(10)?.name)
    }

    @Test
    fun `renamePlaylist rejects blank names`() = runTest {
        playlists.seed(userPlaylist(10, "Ancien"))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertFalse(vm.renamePlaylist(10, "   "))
        assertEquals("Ancien", playlists.playlist(10)?.name)
    }

    @Test
    fun `system playlist cannot be renamed nor deleted`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertFalse(vm.renamePlaylist(Playlist.LIKED_ID, "Autre nom"))
        assertFalse(vm.deletePlaylist(Playlist.LIKED_ID))
        assertEquals("Titres likés", playlists.playlist(Playlist.LIKED_ID)?.name)
    }

    @Test
    fun `deletePlaylist removes a user playlist`() = runTest {
        playlists.seed(userPlaylist(10, "À supprimer"))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.deletePlaylist(10))
        assertNull(playlists.playlist(10))
        assertEquals(listOf(Playlist.LIKED_ID), vm.uiState.value.playlists.map { it.id })
    }

    @Test
    fun `playPlaylist plays tracks in order or shuffled`() = runTest {
        val tracks = listOf(track(1), track(2), track(3))
        playlists.seed(userPlaylist(10, "P", 3), tracks)
        val vm = viewModel()

        vm.playPlaylist(10, shuffle = false)
        vm.playPlaylist(10, shuffle = true)

        verify { playback.play(tracks, 0, false) }
        verify { playback.play(tracks, 0, true) }
    }

    @Test
    fun `playPlaylist does nothing for an empty playlist`() = runTest {
        viewModel().playPlaylist(Playlist.LIKED_ID, shuffle = false)
        verify(exactly = 0) { playback.play(any(), any(), any()) }
    }

    @Test
    fun `playDownloads plays completed downloads from the tapped index`() = runTest {
        downloads.value = listOf(download(1, DownloadState.COMPLETED), download(2, DownloadState.COMPLETED))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playDownloads(startIndex = 1)

        verify { playback.play(listOf(track(1), track(2)), 1, false) }
    }

    @Test
    fun `playHistory plays the tapped track then the following history items`() = runTest {
        history.value = (1..4).map { HistoryEntry(it.toLong(), track(it), 1000L - it) }
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playHistory(1)

        verify { playback.play(listOf(track(2), track(3), track(4)), 0, false) }
    }

    @Test
    fun `history and subscription actions are delegated to the repository`() = runTest {
        val vm = viewModel()
        vm.removeHistoryEntry(7)
        vm.clearHistory()
        vm.unsubscribe("https://youtube.com/@a")

        coVerify { library.removeHistoryEntry(7) }
        coVerify { library.clearHistory() }
        coVerify { library.unsubscribe("https://youtube.com/@a") }
    }
}
