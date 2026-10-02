package com.spautifaille.ui.followed

import app.cash.turbine.test
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FollowedArtistsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val subscriptions = MutableStateFlow<List<Artist>>(emptyList())
    private val library = mockk<LibraryRepository>(relaxed = true) {
        every { observeSubscriptions() } returns subscriptions
    }
    private val messenger = UiMessenger()

    private fun viewModel() = FollowedArtistsViewModel(library, messenger)

    private fun artist(index: Int) = Artist(url = "https://youtube.com/@a$index", name = "Artiste $index")

    @Test
    fun `initial state is loading`() {
        assertTrue(viewModel().uiState.value.isLoading)
    }

    @Test
    fun `exposes the subscriptions`() = runTest {
        subscriptions.value = listOf(artist(1), artist(2))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("Artiste 1", "Artiste 2"), state.artists.map { it.name })
    }

    @Test
    fun `no subscription gives an empty loaded state`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.artists.isEmpty())
    }

    @Test
    fun `unsubscribe removes the artist and tells the user`() = runTest {
        val vm = viewModel()
        messenger.messages.test {
            vm.unsubscribe(artist(1))

            coVerify { library.unsubscribe("https://youtube.com/@a1") }
            assertEquals(UiText.of(R.string.followed_unsubscribed, "Artiste 1"), awaitItem())
        }
    }
}
