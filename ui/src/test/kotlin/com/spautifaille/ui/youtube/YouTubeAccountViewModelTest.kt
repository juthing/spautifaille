package com.spautifaille.ui.youtube

import app.cash.turbine.test
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.SyncError
import com.spautifaille.domain.youtube.SyncStatus
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class YouTubeAccountViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val accounts = FakeAccountRepository()
    private val sync = FakeLibrarySync()
    private val webSession = FakeWebSessionCleaner()
    private val messenger = UiMessenger()

    private fun viewModel() = YouTubeAccountViewModel(accounts, sync, webSession, messenger)

    @Test
    fun `starts not loaded`() {
        assertFalse(viewModel().uiState.value.isLoaded)
    }

    @Test
    fun `signed out state has no account and no problem`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertTrue(state.isLoaded)
        assertNull(state.account)
        assertFalse(state.isSignedIn)
        assertFalse(state.hasProblem)
    }

    @Test
    fun `signed in state exposes the account, the sync status and the likes option`() = runTest {
        accounts.state.value = AccountState.SignedIn(testAccount)
        sync.statusFlow.value = SyncStatus(lastSyncAt = 5L, pendingActions = 3)
        sync.musicOnly.value = true
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertEquals(testAccount, state.account)
        assertTrue(state.isSignedIn)
        assertEquals(3, state.status.pendingActions)
        assertTrue(state.likesMusicOnly)
        assertFalse(state.hasProblem)
    }

    @Test
    fun `a failed sync shows the problem badge`() = runTest {
        accounts.state.value = AccountState.SignedIn(testAccount)
        sync.statusFlow.value = SyncStatus(lastError = SyncError("browse", "illisible", 1L))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.uiState.value.hasProblem)
    }

    @Test
    fun `reauthentication keeps the profile and shows the problem badge`() = runTest {
        accounts.state.value = AccountState.ReauthRequired(testAccount)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertTrue(state.needsReauth)
        assertFalse(state.isSignedIn)
        assertEquals(testAccount, state.account)
        assertTrue(state.hasProblem)
    }

    @Test
    fun `state follows the account being signed out`() = runTest {
        accounts.state.value = AccountState.SignedIn(testAccount)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertEquals(testAccount, vm.uiState.value.account)

        accounts.state.value = AccountState.SignedOut
        assertNull(vm.uiState.value.account)
        assertTrue(vm.uiState.value.isLoaded)
    }

    @Test
    fun `sync now starts a synchronization and tells the user`() = runTest {
        val vm = viewModel()
        messenger.messages.test {
            vm.syncNow()
            assertEquals(1, sync.syncNowCount)
            assertEquals(UiText.of(R.string.yt_sync_started), awaitItem())
        }
    }

    @Test
    fun `likes option is forwarded to the synchronizer`() = runTest {
        val vm = viewModel()
        vm.setLikesMusicOnly(true)
        assertTrue(sync.musicOnly.value)
        vm.setLikesMusicOnly(false)
        assertFalse(sync.musicOnly.value)
    }

    @Test
    fun `sign out clears the account and the web session`() = runTest {
        accounts.state.value = AccountState.SignedIn(testAccount)
        val vm = viewModel()
        messenger.messages.test {
            vm.signOut()
            assertEquals(1, accounts.signOutCount)
            assertEquals(1, webSession.clearCount)
            assertEquals(UiText.of(R.string.yt_signed_out), awaitItem())
        }
    }

    @Test
    fun `web cookies are cleared even when sign out fails`() = runTest {
        val failing = object : com.spautifaille.domain.youtube.AccountRepository by accounts {
            override suspend fun signOut() = error("échec")
        }
        val vm = YouTubeAccountViewModel(failing, sync, webSession, messenger)
        vm.signOut()
        assertEquals(1, webSession.clearCount)
    }
}
