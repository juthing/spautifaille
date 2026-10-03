package com.spautifaille.ui.youtube

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.RemoteLibraryPlaylist
import com.spautifaille.domain.youtube.YouTubePlaylistSync
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.library.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class YouTubePlaylistPickerViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val messenger = UiMessenger()
    private val remote = listOf(
        RemoteLibraryPlaylist("PL1", "Road trip", null, 42, isOwned = true, isLinked = false),
        RemoteLibraryPlaylist("PL2", "Soirée", null, 12, isOwned = true, isLinked = true),
        RemoteLibraryPlaylist("PL3", "Mix d'un ami", null, 100, isOwned = false, isLinked = false),
    )
    private val sync = mockk<YouTubePlaylistSync>(relaxed = true) {
        coEvery { listRemotePlaylists() } returns remote
    }

    private fun viewModel() = YouTubePlaylistPickerViewModel(sync, messenger)

    @Test
    fun `loads the playlists of the account`() {
        val state = viewModel().uiState.value
        assertFalse(state.isLoading)
        assertEquals(remote, state.playlists)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(2, state.selectableCount)
    }

    @Test
    fun `load failure is exposed and retry reloads`() {
        coEvery { sync.listRemotePlaylists() } throws AppException(AppError.Network)
        val vm = viewModel()
        assertEquals(AppError.Network, vm.uiState.value.error)

        coEvery { sync.listRemotePlaylists() } returns remote
        vm.load()
        assertNull(vm.uiState.value.error)
        assertEquals(3, vm.uiState.value.playlists.size)
    }

    @Test
    fun `toggle selects and deselects a playlist`() {
        val vm = viewModel()
        vm.toggle("PL1")
        assertEquals(setOf("PL1"), vm.uiState.value.selectedIds)
        assertTrue(vm.uiState.value.canImport)
        vm.toggle("PL1")
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
        assertFalse(vm.uiState.value.canImport)
    }

    @Test
    fun `an already linked playlist cannot be selected`() {
        val vm = viewModel()
        vm.toggle("PL2")
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `unknown ids are ignored`() {
        val vm = viewModel()
        vm.toggle("nope")
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `select all skips linked playlists and a second call clears`() {
        val vm = viewModel()
        vm.toggleAll()
        assertEquals(setOf("PL1", "PL3"), vm.uiState.value.selectedIds)
        assertTrue(vm.uiState.value.allSelected)
        vm.toggleAll()
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `import sends the selection in display order and closes the screen`() = runTest {
        coEvery { sync.importPlaylists(any()) } returns listOf(10L, 11L)
        val vm = viewModel()
        vm.toggle("PL3")
        vm.toggle("PL1")

        vm.events.test {
            messenger.messages.test {
                vm.import()
                assertEquals(UiText.Resource(R.string.yt_import_done, listOf(2)), awaitItem())
            }
            assertEquals(YouTubePlaylistPickerEvent.Imported(listOf(10L, 11L)), awaitItem())
        }
        coVerify { sync.importPlaylists(listOf("PL1", "PL3")) }
        assertFalse(vm.uiState.value.isImporting)
    }

    @Test
    fun `import without selection does nothing`() {
        val vm = viewModel()
        vm.import()
        coVerify(exactly = 0) { sync.importPlaylists(any()) }
    }

    @Test
    fun `import failure keeps the selection and reports the error`() = runTest {
        coEvery { sync.importPlaylists(any()) } throws AppException(AppError.Network)
        val vm = viewModel()
        vm.toggle("PL1")

        messenger.messages.test {
            vm.import()
            assertEquals(UiText.of(R.string.apperror_network), awaitItem())
        }
        val state = vm.uiState.value
        assertFalse(state.isImporting)
        assertEquals(setOf("PL1"), state.selectedIds)
        assertNotNull(state.playlists.firstOrNull())
    }
}
