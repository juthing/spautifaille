package com.spautifaille.ui.home

import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.ui.library.FakePlaylistRepository
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import com.spautifaille.ui.library.track
import com.spautifaille.ui.network.NetworkMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime

class HomeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val subscriptions = MutableStateFlow<List<Artist>>(emptyList())
    private val online = MutableStateFlow(true)
    private val playlists = FakePlaylistRepository()
    private val library = mockk<LibraryRepository>(relaxed = true) {
        every { observeHistory(any()) } returns history
        every { observeSubscriptions() } returns subscriptions
    }
    private val playback = mockk<PlaybackController>(relaxed = true)
    private val network = object : NetworkMonitor {
        override val isUnmetered: Flow<Boolean> = flowOf(true)
        override val isOnline: Flow<Boolean> = online
    }
    private var now = LocalTime.of(20, 30)

    private fun viewModel() = HomeViewModel(library, playlists, network, playback) { now }

    private fun entry(id: Long, trackIndex: Int) = HistoryEntry(id, track(trackIndex), playedAt = id)

    private fun userPlaylist(id: Long, updatedAt: Long) =
        Playlist(id, "Playlist $id", 0, null, false, createdAt = 0, updatedAt = updatedAt)

    @Test
    fun `initial state is loading with the greeting of the current hour`() {
        now = LocalTime.of(8, 0)
        val state = viewModel().uiState.value
        assertTrue(state.isLoading)
        assertEquals(Greeting.MORNING, state.greeting)
    }

    @Test
    fun `greeting follows the hour of the day`() {
        assertEquals(Greeting.EVENING, HomeViewModel.greetingFor(4))
        assertEquals(Greeting.MORNING, HomeViewModel.greetingFor(5))
        assertEquals(Greeting.MORNING, HomeViewModel.greetingFor(11))
        assertEquals(Greeting.AFTERNOON, HomeViewModel.greetingFor(12))
        assertEquals(Greeting.AFTERNOON, HomeViewModel.greetingFor(17))
        assertEquals(Greeting.EVENING, HomeViewModel.greetingFor(18))
        assertEquals(Greeting.EVENING, HomeViewModel.greetingFor(23))
    }

    @Test
    fun `empty library is a first launch with only the liked shortcut`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isFirstLaunch)
        assertEquals(Greeting.EVENING, state.greeting)
        assertEquals(listOf(Playlist.LIKED_ID), state.shortcuts.map { it.playlistId })
        assertTrue(state.shortcuts.single().isLiked)
    }

    @Test
    fun `recent tracks are distinct and capped`() = runTest {
        // 20 écoutes dont des doublons : le titre 1 est écouté trois fois.
        history.value = listOf(entry(1, 1), entry(2, 2), entry(3, 1)) + (4L..20L).map { entry(it, it.toInt()) } + entry(21, 1)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val recent = vm.uiState.value.recent
        assertEquals(HomeViewModel.RECENT_COUNT, recent.size)
        assertEquals(listOf("video1", "video2", "video4"), recent.take(3).map { it.id })
        assertEquals(recent.size, recent.map { it.id }.toSet().size)
        assertFalse(vm.uiState.value.isFirstLaunch)
    }

    @Test
    fun `shortcuts put liked first then most recently updated playlists up to the cap`() = runTest {
        (10L..18L).forEach { id -> playlists.seed(userPlaylist(id, updatedAt = id)) }
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val shortcuts = vm.uiState.value.shortcuts
        assertEquals(HomeViewModel.SHORTCUT_COUNT, shortcuts.size)
        assertEquals(listOf(Playlist.LIKED_ID, 18L, 17L, 16L, 15L, 14L), shortcuts.map { it.playlistId })
        assertTrue(shortcuts.first().isLiked)
        assertTrue(shortcuts.drop(1).none { it.isLiked })
    }

    @Test
    fun `shortcuts follow playlist changes`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)
        playlists.seed(userPlaylist(10, updatedAt = 5))

        assertEquals(listOf(Playlist.LIKED_ID, 10L), vm.uiState.value.shortcuts.map { it.playlistId })
    }

    @Test
    fun `followed artists are exposed and capped`() = runTest {
        subscriptions.value = (1..20).map { Artist(url = "https://youtube.com/@a$it", name = "Artiste $it") }
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertEquals(HomeViewModel.ARTIST_COUNT, state.artists.size)
        assertEquals("Artiste 1", state.artists.first().name)
        // Un abonnement suffit pour ne plus être au premier lancement.
        assertFalse(state.isFirstLaunch)
    }

    @Test
    fun `offline state follows the network monitor`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertTrue(vm.uiState.value.isOnline)

        online.value = false
        assertFalse(vm.uiState.value.isOnline)

        online.value = true
        assertTrue(vm.uiState.value.isOnline)
    }

    @Test
    fun `clicking a recent track plays the recent list from that track`() = runTest {
        history.value = listOf(entry(1, 1), entry(2, 2), entry(3, 3))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onRecentClick(track(2))

        verify { playback.play(listOf(track(1), track(2), track(3)), startIndex = 1) }
    }

    @Test
    fun `clicking an unknown track plays it alone`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onRecentClick(track(9))

        verify { playback.play(listOf(track(9))) }
    }
}
