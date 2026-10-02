package com.spautifaille.data.youtube

import com.spautifaille.data.local.YouTubeSyncDao
import com.spautifaille.data.youtube.api.YouTubeRemote
import com.spautifaille.data.youtube.session.SyncMetaStore
import com.spautifaille.data.youtube.session.YouTubeSessionStore
import com.spautifaille.data.youtube.sync.SyncCoordinator
import com.spautifaille.data.youtube.sync.SyncScheduler
import com.spautifaille.data.youtube.sync.YouTubeSyncEngine
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.di.ApplicationScope
import com.spautifaille.domain.youtube.AccountRepository
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.LibrarySync
import com.spautifaille.domain.youtube.RemoteLibraryPlaylist
import com.spautifaille.domain.youtube.SyncStatus
import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.domain.youtube.YouTubeCredentials
import com.spautifaille.domain.youtube.YouTubePlaylistSync
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
internal class YouTubeAccountRepository @Inject constructor(
    private val sessions: YouTubeSessionStore,
    private val remote: YouTubeRemote,
    private val syncDao: YouTubeSyncDao,
    private val meta: SyncMetaStore,
    private val scheduler: SyncScheduler,
    private val coordinator: SyncCoordinator,
) : AccountRepository {

    override val accountState: Flow<AccountState> = sessions.state

    override suspend fun signIn(credentials: YouTubeCredentials): YouTubeAccount {
        val account = remote.accountInfo(credentials) ?: throw AppException(AppError.YouTubeAuthRequired)
        val previous = when (val state = sessions.state.first()) {
            is AccountState.SignedIn -> state.account
            is AccountState.ReauthRequired -> state.account
            AccountState.SignedOut -> null
        }
        // Un autre compte que le précédent : les liens et instantanés de l'ancien compte n'ont plus de sens.
        val otherAccount = previous != null && previous.email != null && account.email != null && previous.email != account.email
        coordinator.exclusive(showProgress = false) {
            if (otherAccount) {
                syncDao.clearAccountData()
                meta.clear()
            }
            sessions.save(credentials, account)
        }
        scheduler.schedulePeriodic()
        scheduler.requestFullSync(replace = true)
        return account
    }

    override suspend fun signOut() {
        scheduler.cancelAll()
        coordinator.exclusive(showProgress = false) {
            sessions.clear()
            syncDao.clearAccountData()
            meta.clear()
        }
    }
}

@Singleton
internal class YouTubeLibrarySync internal constructor(
    private val sessions: YouTubeSessionStore,
    private val meta: SyncMetaStore,
    private val syncDao: YouTubeSyncDao,
    private val coordinator: SyncCoordinator,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : LibrarySync {

    @Inject
    constructor(
        sessions: YouTubeSessionStore,
        meta: SyncMetaStore,
        syncDao: YouTubeSyncDao,
        coordinator: SyncCoordinator,
        scheduler: SyncScheduler,
        @ApplicationScope scope: CoroutineScope,
    ) : this(sessions, meta, syncDao, coordinator, scheduler, scope, System::currentTimeMillis)

    override val status: Flow<SyncStatus> = combine(
        meta.lastSyncAt,
        meta.lastError,
        syncDao.observePendingCount(),
        coordinator.isSyncing,
    ) { lastSync, lastError, pending, syncing ->
        SyncStatus(lastSyncAt = lastSync, pendingActions = pending, isSyncing = syncing, lastError = lastError)
    }

    override val likesMusicOnly: Flow<Boolean> = meta.likesMusicOnly

    override suspend fun setLikesMusicOnly(enabled: Boolean) {
        if (meta.likesMusicOnly.first() == enabled) return
        meta.setLikesMusicOnly(enabled)
        syncNow()
    }

    override fun syncNow() {
        scope.launch {
            if (sessions.state.first() is AccountState.SignedIn) scheduler.requestFullSync(replace = true)
        }
    }

    override suspend fun onAppStart() {
        if (sessions.state.first() !is AccountState.SignedIn) return
        scheduler.schedulePeriodic()
        val last = meta.lastSyncAt.first()
        if (last == null || clock() - last > STALE_AFTER_MS) scheduler.requestFullSync()
    }

    private companion object {
        const val STALE_AFTER_MS = 60 * 60 * 1000L
    }
}

@Singleton
internal class YouTubePlaylistLinks @Inject constructor(
    private val engine: YouTubeSyncEngine,
    private val coordinator: SyncCoordinator,
) : YouTubePlaylistSync {

    override suspend fun listRemotePlaylists(): List<RemoteLibraryPlaylist> = engine.listRemotePlaylists()

    override suspend fun importPlaylists(remoteIds: List<String>): List<Long> =
        coordinator.exclusive(showProgress = false) { remoteIds.map { engine.importPlaylist(it) } }

    override suspend fun unlink(playlistId: Long) = coordinator.exclusive(showProgress = false) {
        engine.unlink(playlistId)
    }

    override suspend fun publish(playlistId: Long) = coordinator.exclusive(showProgress = false) {
        engine.publish(playlistId)
    }
}
