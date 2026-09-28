package com.spautifaille.ui.remoteplaylist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.Paged
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.RemotePlaylistPage
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.library.FakePlaylistRepository
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemotePlaylistViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class Token(val page: Int) : PageToken

    private val streams = mockk<StreamRepository>()
    private val playlists = FakePlaylistRepository()
    private val playback = mockk<PlaybackController>(relaxed = true)

    private val remote = RemotePlaylist(URL, "Mix du dimanche", "Chaîne", null, trackCount = 6)

    private fun page(pageIndex: Int, size: Int, next: PageToken?, playlist: RemotePlaylist = remote): RemotePlaylistPage =
        RemotePlaylistPage(
            playlist = playlist,
            tracks = Paged((1..size).map { track(pageIndex * 1000 + it) }, next),
        )

    private fun viewModel() = RemotePlaylistViewModel(
        SavedStateHandle(mapOf(RemotePlaylistViewModel.ARG_URL to URL)),
        streams,
        playlists,
        playback,
    )

    private fun ids(tracks: List<Track>) = tracks.map { it.id }

    @Test
    fun `loads the first page`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 3, Token(1))

        val vm = viewModel()

        val state = vm.uiState.value
        assertEquals(RemotePlaylistStatus.Content, state.status)
        assertEquals("Mix du dimanche", state.playlist?.name)
        assertEquals(3, state.tracks.size)
        assertTrue(state.hasMore)
    }

    @Test
    fun `loadMore appends the next page until the end`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 3, Token(1))
        coEvery { streams.remotePlaylist(URL, Token(1)) } returns page(1, 3, null)
        val vm = viewModel()

        vm.loadMore()

        val state = vm.uiState.value
        assertEquals(listOf("video1", "video2", "video3", "video1001", "video1002", "video1003"), ids(state.tracks))
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)

        // Plus de page : aucun nouvel appel réseau.
        vm.loadMore()
        coVerify(exactly = 1) { streams.remotePlaylist(URL, Token(1)) }
    }

    @Test
    fun `loadMore ignores concurrent requests while a page is loading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 2, Token(1))
        coEvery { streams.remotePlaylist(URL, Token(1)) } coAnswers {
            gate.await()
            page(1, 2, null)
        }
        val vm = viewModel()

        vm.loadMore()
        assertTrue(vm.uiState.value.isLoadingMore)
        vm.loadMore()
        gate.complete(Unit)

        coVerify(exactly = 1) { streams.remotePlaylist(URL, Token(1)) }
        assertEquals(4, vm.uiState.value.tracks.size)
    }

    @Test
    fun `loadMore failure is surfaced and can be retried`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 2, Token(1))
        coEvery { streams.remotePlaylist(URL, Token(1)) } throws AppException(AppError.Network)
        val vm = viewModel()

        vm.loadMore()

        assertEquals(AppError.Network, vm.uiState.value.loadMoreError)
        assertEquals(2, vm.uiState.value.tracks.size)
        assertTrue(vm.uiState.value.hasMore)

        coEvery { streams.remotePlaylist(URL, Token(1)) } returns page(1, 2, null)
        vm.loadMore()

        assertNull(vm.uiState.value.loadMoreError)
        assertEquals(4, vm.uiState.value.tracks.size)
    }

    @Test
    fun `initial failure gives an error state and load retries`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } throws AppException(AppError.Network)
        val vm = viewModel()
        assertEquals(RemotePlaylistStatus.Error(AppError.Network), vm.uiState.value.status)

        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 2, null)
        vm.load()

        assertEquals(RemotePlaylistStatus.Content, vm.uiState.value.status)
        assertEquals(2, vm.uiState.value.tracks.size)
    }

    @Test
    fun `unexpected exceptions are mapped to Unknown`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } throws IllegalStateException("oups")
        val vm = viewModel()
        assertEquals(RemotePlaylistStatus.Error(AppError.Unknown("oups")), vm.uiState.value.status)
    }

    @Test
    fun `play and shuffle use the loaded tracks`() = runTest {
        val first = page(0, 3, null)
        coEvery { streams.remotePlaylist(URL, null) } returns first
        val vm = viewModel()

        vm.playAll(shuffle = false)
        vm.playAll(shuffle = true)
        vm.playFrom(2)

        verify { playback.play(first.tracks.items, 0, false) }
        verify { playback.play(first.tracks.items, 0, true) }
        verify { playback.play(first.tracks.items, 2, false) }
    }

    @Test
    fun `saveToLibrary loads every page then creates a local playlist`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 2, Token(1))
        coEvery { streams.remotePlaylist(URL, Token(1)) } returns page(1, 2, Token(2))
        coEvery { streams.remotePlaylist(URL, Token(2)) } returns page(2, 2, null)
        val vm = viewModel()

        vm.events.test {
            vm.saveToLibrary()
            val event = awaitItem() as RemotePlaylistEvent.Saved
            assertEquals("Mix du dimanche", event.name)
            assertEquals(6, event.trackCount)
            assertEquals(6, playlists.tracksOf(event.playlistId).size)
        }
        assertEquals(listOf("Mix du dimanche"), playlists.createdNames)
        assertFalse(vm.uiState.value.isSaving)
        assertFalse(vm.uiState.value.hasMore)
        assertEquals(6, vm.uiState.value.tracks.size)
    }

    @Test
    fun `saveToLibrary exposes progress while pages load`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 2, Token(1))
        coEvery { streams.remotePlaylist(URL, Token(1)) } coAnswers {
            gate.await()
            page(1, 4, null)
        }
        val vm = viewModel()

        vm.saveToLibrary()

        val saving = vm.uiState.value
        assertTrue(saving.isSaving)
        assertEquals(2, saving.saveProgress)
        assertEquals(6, saving.saveTarget)

        // Un second appel pendant l'enregistrement est ignoré.
        vm.saveToLibrary()
        gate.complete(Unit)

        assertFalse(vm.uiState.value.isSaving)
        assertEquals(1, playlists.createdNames.size)
    }

    @Test
    fun `saveToLibrary caps the playlist at 1000 tracks`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 400, Token(1))
        coEvery { streams.remotePlaylist(URL, Token(1)) } returns page(1, 400, Token(2))
        coEvery { streams.remotePlaylist(URL, Token(2)) } returns page(2, 400, Token(3))
        coEvery { streams.remotePlaylist(URL, Token(3)) } returns page(3, 400, null)
        val vm = viewModel()

        vm.events.test {
            vm.saveToLibrary()
            val event = awaitItem() as RemotePlaylistEvent.Saved
            assertEquals(RemotePlaylistViewModel.MAX_SAVED_TRACKS, event.trackCount)
            assertEquals(1000, playlists.tracksOf(event.playlistId).size)
        }
        // 3 pages suffisent (1200 ≥ 1000) : la 4e n'est jamais demandée.
        coVerify(exactly = 0) { streams.remotePlaylist(URL, Token(3)) }
    }

    @Test
    fun `saveToLibrary reports a page failure and creates nothing`() = runTest {
        coEvery { streams.remotePlaylist(URL, null) } returns page(0, 2, Token(1))
        coEvery { streams.remotePlaylist(URL, Token(1)) } throws AppException(AppError.BotDetected)
        val vm = viewModel()

        vm.events.test {
            vm.saveToLibrary()
            assertEquals(RemotePlaylistEvent.SaveFailed(AppError.BotDetected), awaitItem())
        }
        assertTrue(playlists.createdNames.isEmpty())
        assertFalse(vm.uiState.value.isSaving)
    }

    private companion object {
        const val URL = "https://www.youtube.com/playlist?list=PL123"
    }
}
