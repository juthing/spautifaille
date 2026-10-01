package com.spautifaille.ui.library

import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.network.NetworkMonitor
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val playlists = FakePlaylistRepository()
    private val downloads = MutableStateFlow<List<Download>>(emptyList())
    private val online = MutableStateFlow(true)

    private val downloadRepository = mockk<DownloadRepository>(relaxed = true) {
        every { observeDownloads() } returns downloads
    }
    private val playback = mockk<PlaybackController>(relaxed = true)
    private val notificationPermission = mockk<NotificationPermissionRequester>(relaxed = true)
    private val messenger = UiMessenger()
    private val network = object : NetworkMonitor {
        override val isUnmetered: Flow<Boolean> = online
        override val isOnline: Flow<Boolean> = online
    }

    private fun viewModel() =
        LibraryViewModel(playlists, downloadRepository, playback, network, notificationPermission, messenger)

    private fun userPlaylist(id: Long, name: String, count: Int = 0) =
        Playlist(id, name, count, null, false, 0, 0)

    private fun download(index: Int, state: DownloadState = DownloadState.COMPLETED) =
        Download(track(index), state, 1f, 10, 10, "/f$index", null, index.toLong())

    @Test
    fun `initial state is loading`() {
        assertTrue(viewModel().uiState.value.isLoading)
    }

    @Test
    fun `pinned playlists come first then user playlists`() = runTest {
        playlists.seed(userPlaylist(10, "Road trip", 2), listOf(track(1), track(2)))
        playlists.seed(userPlaylist(11, "Focus"))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isOffline)
        assertEquals(listOf(Playlist.LIKED_ID, Playlist.DOWNLOADED_ID, 10L, 11L), state.playlists.map { it.id })
        assertTrue(state.hasUserPlaylists)
    }

    @Test
    fun `downloaded playlist counts completed downloads only`() = runTest {
        downloads.value = listOf(
            download(1),
            download(2, DownloadState.RUNNING),
            download(3, DownloadState.FAILED),
            download(4),
        )
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val downloaded = vm.uiState.value.playlists.single { it.id == Playlist.DOWNLOADED_ID }
        assertEquals(2, downloaded.trackCount)
        assertTrue(downloaded.isSystem)
    }

    @Test
    fun `without user playlists the state reports the empty case`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertFalse(vm.uiState.value.hasUserPlaylists)
        assertEquals(listOf(Playlist.LIKED_ID, Playlist.DOWNLOADED_ID), vm.uiState.value.playlists.map { it.id })
    }

    @Test
    fun `offline puts Downloaded first and back online restores the order`() = runTest {
        playlists.seed(userPlaylist(10, "Road trip"))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        online.value = false
        assertTrue(vm.uiState.value.isOffline)
        assertEquals(listOf(Playlist.DOWNLOADED_ID, Playlist.LIKED_ID, 10L), vm.uiState.value.playlists.map { it.id })

        online.value = true
        assertFalse(vm.uiState.value.isOffline)
        assertEquals(listOf(Playlist.LIKED_ID, Playlist.DOWNLOADED_ID, 10L), vm.uiState.value.playlists.map { it.id })
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
    fun `pinned playlists cannot be renamed nor deleted`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertFalse(vm.renamePlaylist(Playlist.LIKED_ID, "Autre nom"))
        assertFalse(vm.deletePlaylist(Playlist.LIKED_ID))
        assertFalse(vm.renamePlaylist(Playlist.DOWNLOADED_ID, "Autre nom"))
        assertFalse(vm.deletePlaylist(Playlist.DOWNLOADED_ID))
        assertEquals("Titres likés", playlists.playlist(Playlist.LIKED_ID)?.name)
    }

    @Test
    fun `deletePlaylist removes a user playlist`() = runTest {
        playlists.seed(userPlaylist(10, "À supprimer"))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.deletePlaylist(10))
        assertNull(playlists.playlist(10))
        assertEquals(listOf(Playlist.LIKED_ID, Playlist.DOWNLOADED_ID), vm.uiState.value.playlists.map { it.id })
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
    fun `playPlaylist on Downloaded plays completed downloads`() = runTest {
        downloads.value = listOf(download(1), download(2, DownloadState.RUNNING), download(3))
        val vm = viewModel()

        vm.playPlaylist(Playlist.DOWNLOADED_ID, shuffle = true)

        // Du plus récent au plus ancien.
        verify { playback.play(listOf(track(3), track(1)), 0, true) }
    }

    @Test
    fun `offline playPlaylist only plays downloaded tracks`() = runTest {
        val tracks = listOf(track(1), track(2), track(3))
        playlists.seed(userPlaylist(10, "P", 3), tracks)
        downloads.value = listOf(download(2))
        online.value = false

        viewModel().playPlaylist(10, shuffle = false)

        verify { playback.play(listOf(track(2)), 0, false) }
    }

    @Test
    fun `offline playPlaylist with nothing downloaded plays nothing`() = runTest {
        playlists.seed(userPlaylist(10, "P", 2), listOf(track(1), track(2)))
        online.value = false

        viewModel().playPlaylist(10, shuffle = false)

        verify(exactly = 0) { playback.play(any(), any(), any()) }
    }

    @Test
    fun `downloadPlaylist enqueues only tracks not downloaded yet`() = runTest {
        val tracks = listOf(track(1), track(2), track(3))
        playlists.seed(userPlaylist(10, "P", 3), tracks)
        downloads.value = listOf(download(1))
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { messenger.messages.collect { messages += it } }

        viewModel().downloadPlaylist(10)

        coVerify { downloadRepository.enqueue(listOf(track(2), track(3))) }
        verify(exactly = 1) { notificationPermission.requestIfNeeded() }
        assertEquals(listOf<UiText>(UiText.of(R.string.lib_downloads_started)), messages)
    }

    @Test
    fun `downloadPlaylist tells when everything is already downloaded`() = runTest {
        playlists.seed(userPlaylist(10, "P", 1), listOf(track(1)))
        downloads.value = listOf(download(1))
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { messenger.messages.collect { messages += it } }

        viewModel().downloadPlaylist(10)

        coVerify(exactly = 0) { downloadRepository.enqueue(any()) }
        assertEquals(listOf<UiText>(UiText.of(R.string.lib_downloads_nothing_to_do)), messages)
    }

    @Test
    fun `downloadPlaylist does nothing offline or for Downloaded`() = runTest {
        playlists.seed(userPlaylist(10, "P", 1), listOf(track(1)))
        val vm = viewModel()

        vm.downloadPlaylist(Playlist.DOWNLOADED_ID)
        online.value = false
        vm.downloadPlaylist(10)

        coVerify(exactly = 0) { downloadRepository.enqueue(any()) }
    }
}
