package com.spautifaille.ui.discovery

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.recommendation.DISCOVERY_REFRESH_INTERVAL_MS
import com.spautifaille.domain.recommendation.Discovery
import com.spautifaille.domain.recommendation.DiscoveryRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import com.spautifaille.ui.library.track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiscoveryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val now = 10L * DISCOVERY_REFRESH_INTERVAL_MS
    private val discovery = MutableStateFlow<Discovery?>(null)
    private val repository = mockk<DiscoveryRepository>(relaxed = true) {
        every { observe() } returns discovery
    }
    private val playback = mockk<PlaybackController>(relaxed = true)
    private val messenger = UiMessenger()
    private val tracks = (1..5).map { track(it) }

    private fun viewModel() = DiscoveryViewModel(repository, playback, messenger) { now }

    private fun fresh() = Discovery(tracks, generatedAt = now - 1_000)
    private fun stale() = Discovery(tracks, generatedAt = now - DISCOVERY_REFRESH_INTERVAL_MS - 1)

    @Test
    fun `schedules the periodic refresh on init`() = runTest {
        discovery.value = fresh()
        viewModel()
        verify(exactly = 1) { repository.scheduleRefresh() }
    }

    @Test
    fun `starts loading then shows the cached content`() = runTest {
        val vm = viewModel()
        assertEquals(DiscoveryStatus.LOADING, vm.uiState.value.status)

        discovery.value = fresh()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertEquals(DiscoveryStatus.CONTENT, state.status)
        assertEquals(tracks, state.tracks)
        assertEquals(now - 1_000, state.generatedAt)
    }

    @Test
    fun `refreshes on open when there is no cache and shows the empty state afterwards`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        coVerify(exactly = 1) { repository.refresh() }
        val state = vm.uiState.value
        assertEquals(DiscoveryStatus.EMPTY, state.status)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `refreshes on open when the cache is stale`() = runTest {
        discovery.value = stale()
        viewModel()
        coVerify(exactly = 1) { repository.refresh() }
    }

    @Test
    fun `does not refresh on open when the cache is fresh`() = runTest {
        discovery.value = fresh()
        viewModel()
        coVerify(exactly = 0) { repository.refresh() }
    }

    @Test
    fun `refresh error without content is exposed as error state and cleared by a retry`() = runTest {
        coEvery { repository.refresh() } throws AppException(AppError.Network)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertEquals(DiscoveryStatus.ERROR, vm.uiState.value.status)
        assertEquals(AppError.Network, vm.uiState.value.error)

        coEvery { repository.refresh() } returns Unit
        vm.refresh()

        assertEquals(DiscoveryStatus.EMPTY, vm.uiState.value.status)
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun `refresh error with content keeps the list and shows a message`() = runTest {
        discovery.value = fresh()
        coEvery { repository.refresh() } throws AppException(AppError.BotDetected)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        messenger.messages.test {
            vm.refresh()
            assertEquals(UiText.Resource(R.string.apperror_bot_detected), awaitItem())
        }
        assertEquals(DiscoveryStatus.CONTENT, vm.uiState.value.status)
        assertEquals(null, vm.uiState.value.error)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `isRefreshing follows the refresh and concurrent requests are ignored`() = runTest {
        discovery.value = fresh()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.refresh() } coAnswers { gate.await() }
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.refresh()
        vm.refresh()
        assertTrue(vm.uiState.value.isRefreshing)
        coVerify(exactly = 1) { repository.refresh() }

        gate.complete(Unit)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `playAll starts from the first track with the requested shuffle`() = runTest {
        discovery.value = fresh()
        val vm = viewModel()

        vm.playAll(shuffle = false)
        vm.playAll(shuffle = true)

        verify { playback.play(tracks, 0, false) }
        verify { playback.play(tracks, 0, true) }
    }

    @Test
    fun `playFrom plays the whole list from the tapped index`() = runTest {
        discovery.value = fresh()
        val vm = viewModel()

        vm.playFrom(3)
        vm.playFrom(99)

        verify { playback.play(tracks, 3, false) }
        verify { playback.play(tracks, 4, false) }
    }

    @Test
    fun `play actions are ignored without content`() = runTest {
        val vm = viewModel()
        vm.playAll(true)
        vm.playFrom(0)
        verify(exactly = 0) { playback.play(any(), any(), any()) }
    }
}
