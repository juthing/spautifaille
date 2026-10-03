package com.spautifaille.ui.youtube

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.ui.R
import com.spautifaille.ui.library.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class YouTubeLoginViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val accounts = FakeAccountRepository()
    private val webSession = FakeWebSessionCleaner()

    private fun viewModel() = YouTubeLoginViewModel(accounts, webSession)

    @Test
    fun `valid credentials sign in, clear the web session and close the screen`() = runTest {
        val vm = viewModel()
        vm.events.test {
            vm.onCredentials(testCredentials)
            assertEquals(YouTubeLoginEvent.SignedIn, awaitItem())
        }
        assertEquals(listOf(testCredentials), accounts.signInRequests)
        assertEquals(1, webSession.clearCount)
        assertFalse(vm.uiState.value.isChecking)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `refused credentials show a message and keep the web session`() = runTest {
        accounts.signInFailure = AppException(AppError.YouTubeAuthRequired)
        val vm = viewModel()
        vm.onCredentials(testCredentials)

        val state = vm.uiState.value
        assertEquals(R.string.yt_login_refused, state.errorMessage)
        assertFalse(state.isChecking)
        assertEquals(0, webSession.clearCount)
    }

    @Test
    fun `other errors use the common message`() = runTest {
        accounts.signInFailure = AppException(AppError.Network)
        val vm = viewModel()
        vm.onCredentials(testCredentials)

        assertEquals(R.string.apperror_network, vm.uiState.value.errorMessage)
    }

    @Test
    fun `capture failure shows its own message`() {
        val vm = viewModel()
        vm.onCaptureFailed()
        assertEquals(R.string.yt_login_capture_failed, vm.uiState.value.errorMessage)
    }

    @Test
    fun `retry clears the message and reloads the page`() {
        val vm = viewModel()
        vm.onCaptureFailed()
        val before = vm.uiState.value.attempt
        vm.retry()

        assertNull(vm.uiState.value.errorMessage)
        assertEquals(before + 1, vm.uiState.value.attempt)
    }

    @Test
    fun `a second capture while checking is ignored`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : com.spautifaille.domain.youtube.AccountRepository by accounts {
            override suspend fun signIn(credentials: com.spautifaille.domain.youtube.YouTubeCredentials): com.spautifaille.domain.youtube.YouTubeAccount {
                accounts.signInRequests += credentials
                gate.await()
                return testAccount
            }
        }
        val vm = YouTubeLoginViewModel(slow, webSession)
        vm.onCredentials(testCredentials)
        assertTrue(vm.uiState.value.isChecking)
        vm.onCredentials(testCredentials)

        assertEquals(1, accounts.signInRequests.size)
        gate.complete(Unit)
    }
}
