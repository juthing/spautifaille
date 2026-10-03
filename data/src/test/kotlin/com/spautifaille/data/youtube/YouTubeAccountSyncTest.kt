package com.spautifaille.data.youtube

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.YtPlaylistLinkEntity
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.track
import com.spautifaille.data.repository.PlaylistRepositoryImpl
import com.spautifaille.data.youtube.sync.SyncCoordinator
import com.spautifaille.data.youtube.sync.YouTubeSyncEngine
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.SyncError
import com.spautifaille.domain.youtube.YouTubeAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class YouTubeAccountSyncTest {

    private lateinit var db: SpautifailleDatabase
    private lateinit var sessions: FakeSessionStore
    private lateinit var remote: FakeYouTubeRemote
    private lateinit var meta: FakeMetaStore
    private lateinit var scheduler: FakeScheduler
    private lateinit var coordinator: SyncCoordinator
    private var now = 10_000_000L

    @Before
    fun setUp() {
        db = createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        sessions = FakeSessionStore(AccountState.SignedOut).also { it.storedCredentials = null }
        remote = FakeYouTubeRemote()
        meta = FakeMetaStore()
        scheduler = FakeScheduler()
        coordinator = SyncCoordinator()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun accounts() = YouTubeAccountRepository(sessions, remote, db.youTubeSyncDao(), meta, scheduler, coordinator)

    private fun librarySync(scope: CoroutineScope) =
        YouTubeLibrarySync(sessions, meta, db.youTubeSyncDao(), coordinator, scheduler, scope) { now }

    // region Compte

    @Test
    fun `connexion reussie - stocke les identifiants, planifie la synchro periodique et une premiere synchro`() = runTest {
        val account = accounts().signIn(testCredentials)

        assertEquals(testAccount, account)
        assertEquals(AccountState.SignedIn(testAccount), sessions.stateFlow.value)
        assertEquals(testCredentials, sessions.storedCredentials)
        assertEquals(1, scheduler.periodic)
        assertEquals(1, scheduler.fullSyncs)
    }

    @Test
    fun `connexion refusee - rien n est stocke ni planifie`() = runTest {
        remote.account = null

        val error = runCatching { accounts().signIn(testCredentials) }.exceptionOrNull() as AppException

        assertEquals(AppError.YouTubeAuthRequired, error.error)
        assertEquals(AccountState.SignedOut, sessions.stateFlow.value)
        assertNull(sessions.storedCredentials)
        assertEquals(0, scheduler.fullSyncs)
    }

    @Test
    fun `reconnexion avec le meme compte - conserve liens et instantanes`() = runTest {
        accounts().signIn(testCredentials)
        val playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), { ++now })
        val id = playlists.create("Liée")
        db.youTubeSyncDao().upsertLink(YtPlaylistLinkEntity(id, "PL1", false, 1, null))
        sessions.markReauthRequired()

        accounts().signIn(testCredentials)

        assertEquals(AccountState.SignedIn(testAccount), sessions.stateFlow.value)
        assertEquals(1, db.youTubeSyncDao().allLinks().size)
    }

    @Test
    fun `connexion avec un autre compte - oublie les liens de l ancien`() = runTest {
        accounts().signIn(testCredentials)
        val playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), { ++now })
        val id = playlists.create("Liée")
        db.youTubeSyncDao().upsertLink(YtPlaylistLinkEntity(id, "PL1", false, 1, null))
        db.youTubeSyncDao().replaceSnapshot("LIKES", listOf("a"))
        remote.account = YouTubeAccount("Autre", "autre@example.com", null, null)

        accounts().signIn(testCredentials)

        assertTrue(db.youTubeSyncDao().allLinks().isEmpty())
        assertNull(db.youTubeSyncDao().snapshot("LIKES"))
        assertEquals("autre@example.com", (sessions.stateFlow.value as AccountState.SignedIn).account.email)
    }

    @Test
    fun `deconnexion - efface compte, file, instantanes et liens, garde les donnees locales`() = runTest {
        accounts().signIn(testCredentials)
        val playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), { ++now })
        val id = playlists.create("Liée", listOf(track("a")))
        db.youTubeSyncDao().upsertLink(YtPlaylistLinkEntity(id, "PL1", false, 1, null))
        db.youTubeSyncDao().replaceSnapshot("LIKES", listOf("a"))
        db.youTubeSyncDao().enqueue("LIKE", "a", true, 1)
        meta.last.value = 5

        accounts().signOut()

        assertEquals(AccountState.SignedOut, sessions.stateFlow.value)
        assertNull(sessions.storedCredentials)
        assertTrue(db.youTubeSyncDao().allLinks().isEmpty())
        assertNull(db.youTubeSyncDao().snapshot("LIKES"))
        assertTrue(db.youTubeSyncDao().pendingActions().isEmpty())
        assertNull(meta.last.value)
        assertEquals(1, scheduler.cancelled)
        // La playlist et ses titres restent.
        assertEquals(listOf("a"), db.youTubeSyncDao().entryRows(id).map { it.trackId })
    }

    // endregion

    // region Synchronisation

    @Test
    fun `synchro maintenant - demande une synchro complete seulement si connecte`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sync = librarySync(scope)

        sync.syncNow()
        scope.advanceUntilIdle()
        assertEquals(0, scheduler.fullSyncs)

        sessions.stateFlow.value = AccountState.SignedIn(testAccount)
        sync.syncNow()
        scope.advanceUntilIdle()
        assertEquals(1, scheduler.fullSyncs)
        assertEquals("une demande manuelle remplace un travail en attente", 1, scheduler.replaced)
    }

    @Test
    fun `demarrage - planifie le periodique et lance une synchro si la derniere date de plus d une heure`() = runTest {
        sessions.stateFlow.value = AccountState.SignedIn(testAccount)
        val sync = librarySync(TestScope(StandardTestDispatcher(testScheduler)))

        meta.last.value = now - 30 * 60 * 1000L
        sync.onAppStart()
        assertEquals(1, scheduler.periodic)
        assertEquals("synchro récente : pas de nouvelle demande", 0, scheduler.fullSyncs)

        meta.last.value = now - 61 * 60 * 1000L
        sync.onAppStart()
        assertEquals(1, scheduler.fullSyncs)

        meta.last.value = null
        sync.onAppStart()
        assertEquals(2, scheduler.fullSyncs)
    }

    @Test
    fun `demarrage sans compte ou a reconnecter - ne planifie rien`() = runTest {
        val sync = librarySync(TestScope(StandardTestDispatcher(testScheduler)))

        sync.onAppStart()
        sessions.stateFlow.value = AccountState.ReauthRequired(testAccount)
        sync.onAppStart()

        assertEquals(0, scheduler.periodic)
        assertEquals(0, scheduler.fullSyncs)
    }

    @Test
    fun `reglage des likes - persiste et declenche une synchro, sans effet s il ne change pas`() = runTest {
        sessions.stateFlow.value = AccountState.SignedIn(testAccount)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sync = librarySync(scope)

        sync.setLikesMusicOnly(true)
        scope.advanceUntilIdle()
        assertTrue(sync.likesMusicOnly.first())
        assertEquals(1, scheduler.fullSyncs)

        sync.setLikesMusicOnly(true)
        scope.advanceUntilIdle()
        assertEquals(1, scheduler.fullSyncs)
    }

    @Test
    fun `statut - derniere synchro, erreur, actions en attente et indicateur`() = runTest {
        val sync = librarySync(TestScope(StandardTestDispatcher(testScheduler)))

        sync.status.test {
            assertEquals(false, awaitItem().isSyncing)
            meta.last.value = 42
            assertEquals(42L, awaitItem().lastSyncAt)
            meta.error.value = SyncError("browse", "échec", 1)
            assertEquals("browse", awaitItem().lastError!!.endpoint)
            db.youTubeSyncDao().enqueue("LIKE", "a", true, 1)
            assertEquals(1, awaitItem().pendingActions)
            coordinator.exclusive(showProgress = true) {
                assertTrue(awaitItem().isSyncing)
            }
            assertFalse(awaitItem().isSyncing)
        }
    }

    // endregion

    // region Playlists liées (API domaine)

    @Test
    fun `playlists liees - liste, import, publication et liaison coupee`() = runTest {
        sessions.stateFlow.value = AccountState.SignedIn(testAccount)
        remote.addPlaylist("PL1", "Road trip", owned = true, "x", "y")
        remote.libraryEntries += com.spautifaille.data.youtube.api.RemoteLibraryEntry("PL1", "Road trip", null, 2, true)
        val engine = YouTubeSyncEngine(
            db, db.trackDao(), db.playlistDao(), db.subscriptionDao(), db.youTubeSyncDao(), remote, meta, { ++now },
        )
        val links = YouTubePlaylistLinks(engine, coordinator)

        assertFalse(links.listRemotePlaylists().single().isLinked)
        val ids = links.importPlaylists(listOf("PL1"))
        assertTrue(links.listRemotePlaylists().single().isLinked)
        assertEquals(1, ids.size)
        assertEquals(listOf("x", "y"), db.youTubeSyncDao().entryRows(ids.single()).map { it.trackId })

        links.unlink(ids.single())
        assertTrue(db.youTubeSyncDao().allLinks().isEmpty())

        val local = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), { ++now }).create("Locale", listOf(track("a")))
        links.publish(local)
        assertEquals(local, db.youTubeSyncDao().allLinks().single().playlistId)
    }

    // endregion
}
