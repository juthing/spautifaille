package com.spautifaille.ui.youtube

import com.spautifaille.domain.youtube.AccountRepository
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.LibrarySync
import com.spautifaille.domain.youtube.SyncStatus
import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.domain.youtube.YouTubeCredentials
import kotlinx.coroutines.flow.MutableStateFlow

val testAccount = YouTubeAccount(
    name = "Camille Martin",
    email = "camille@example.com",
    handle = "@camille",
    avatarUrl = "https://example.com/avatar.jpg",
)

val testCredentials = YouTubeCredentials(cookie = "SAPISID=abc", visitorData = "visitor", dataSyncId = "sync")

/** Compte en mémoire : l'état est piloté par le test. */
class FakeAccountRepository(initial: AccountState = AccountState.SignedOut) : AccountRepository {
    val state = MutableStateFlow(initial)
    override val accountState = state

    val signInRequests = mutableListOf<YouTubeCredentials>()
    var signInFailure: Throwable? = null
    var signOutCount = 0

    override suspend fun signIn(credentials: YouTubeCredentials): YouTubeAccount {
        signInRequests += credentials
        signInFailure?.let { throw it }
        state.value = AccountState.SignedIn(testAccount)
        return testAccount
    }

    override suspend fun signOut() {
        signOutCount++
        state.value = AccountState.SignedOut
    }
}

class FakeLibrarySync : LibrarySync {
    val statusFlow = MutableStateFlow(SyncStatus())
    override val status = statusFlow
    val musicOnly = MutableStateFlow(false)
    override val likesMusicOnly = musicOnly

    var syncNowCount = 0
    var appStartCount = 0

    override suspend fun setLikesMusicOnly(enabled: Boolean) {
        musicOnly.value = enabled
    }

    override fun syncNow() {
        syncNowCount++
    }

    override suspend fun onAppStart() {
        appStartCount++
    }
}

class FakeWebSessionCleaner : WebSessionCleaner {
    var clearCount = 0
    override suspend fun clear() {
        clearCount++
    }
}
