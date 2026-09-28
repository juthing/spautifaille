package com.spautifaille.ui.artist

import androidx.lifecycle.SavedStateHandle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.ArtistDetails
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import com.spautifaille.ui.library.track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ArtistViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val subscribed = MutableStateFlow(false)
    private val streams = mockk<StreamRepository>()
    private val library = mockk<LibraryRepository>(relaxed = true) {
        every { observeIsSubscribed(URL) } returns subscribed
    }
    private val playback = mockk<PlaybackController>(relaxed = true)

    private val details = ArtistDetails(
        artist = Artist("https://canonical/artist", "Artiste", null, 1200),
        tracks = listOf(track(1), track(2), track(3)),
    )

    private fun viewModel() = ArtistViewModel(
        SavedStateHandle(mapOf(ArtistViewModel.ARG_URL to URL)),
        streams,
        library,
        playback,
    )

    @Test
    fun `loads artist details and subscription state`() = runTest {
        coEvery { streams.artist(URL) } returns details
        subscribed.value = true
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertEquals(ArtistStatus.Content(details), state.status)
        assertTrue(state.isSubscribed)
    }

    @Test
    fun `failure shows an error and load retries`() = runTest {
        coEvery { streams.artist(URL) } throws AppException(AppError.Network)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertEquals(ArtistStatus.Error(AppError.Network), vm.uiState.value.status)

        coEvery { streams.artist(URL) } returns details
        vm.load()

        assertEquals(ArtistStatus.Content(details), vm.uiState.value.status)
    }

    @Test
    fun `toggleSubscription subscribes under the navigation url then unsubscribes`() = runTest {
        coEvery { streams.artist(URL) } returns details
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.toggleSubscription()
        coVerify { library.subscribe(details.artist.copy(url = URL)) }

        subscribed.value = true
        vm.toggleSubscription()
        coVerify { library.unsubscribe(URL) }
    }

    @Test
    fun `play and shuffle use the artist tracks`() = runTest {
        coEvery { streams.artist(URL) } returns details
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playFrom(1)
        vm.playAll(shuffle = true)

        verify { playback.play(details.tracks, 1, false) }
        verify { playback.play(details.tracks, 0, true) }
    }

    private companion object {
        const val URL = "https://www.youtube.com/@artist"
    }
}
