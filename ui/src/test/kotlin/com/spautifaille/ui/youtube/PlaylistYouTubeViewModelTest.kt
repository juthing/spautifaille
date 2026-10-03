package com.spautifaille.ui.youtube

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.YouTubePlaylistSync
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaylistYouTubeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val accounts = FakeAccountRepository()
    private val sync = mockk<YouTubePlaylistSync>(relaxed = true)
    private val messenger = UiMessenger()

    private fun viewModel() = PlaylistYouTubeViewModel(SavedStateHandle(mapOf("id" to 7L)), accounts, sync, messenger)

    @Test
    fun `publishing is only offered when signed in`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.canPublish)
        assertFalse(vm.canPublish.value)

        accounts.state.value = AccountState.SignedIn(testAccount)
        assertTrue(vm.canPublish.value)

        accounts.state.value = AccountState.ReauthRequired(testAccount)
        assertFalse(vm.canPublish.value)
    }

    @Test
    fun `unlink targets the playlist of the route and confirms`() = runTest {
        val vm = viewModel()
        messenger.messages.test {
            vm.unlink()
            assertEquals(UiText.of(R.string.yt_unlinked), awaitItem())
        }
        coVerify { sync.unlink(7L) }
    }

    @Test
    fun `publish creates the playlist on YouTube and confirms`() = runTest {
        val vm = viewModel()
        messenger.messages.test {
            vm.publish()
            assertEquals(UiText.of(R.string.yt_published), awaitItem())
        }
        coVerify { sync.publish(7L) }
        assertFalse(vm.busy.value)
    }

    @Test
    fun `publish failure is reported and frees the button`() = runTest {
        coEvery { sync.publish(any()) } throws AppException(AppError.YouTubeAuthRequired)
        val vm = viewModel()
        messenger.messages.test {
            vm.publish()
            assertEquals(UiText.of(R.string.apperror_youtube_auth_required), awaitItem())
        }
        assertFalse(vm.busy.value)
    }

    @Test
    fun `unlink failure is reported`() = runTest {
        coEvery { sync.unlink(any()) } throws AppException(AppError.Network)
        val vm = viewModel()
        messenger.messages.test {
            vm.unlink()
            assertEquals(UiText.of(R.string.apperror_network), awaitItem())
        }
    }
}
