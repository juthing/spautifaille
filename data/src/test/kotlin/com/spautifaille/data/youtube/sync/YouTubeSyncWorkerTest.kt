package com.spautifaille.data.youtube.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.track
import com.spautifaille.data.repository.LibraryRepositoryImpl
import com.spautifaille.data.youtube.FakeMetaStore
import com.spautifaille.data.youtube.FakeSessionStore
import com.spautifaille.data.youtube.FakeYouTubeRemote
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.youtube.AccountState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class YouTubeSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var db: SpautifailleDatabase
    private lateinit var sessions: FakeSessionStore
    private lateinit var remote: FakeYouTubeRemote
    private lateinit var meta: FakeMetaStore
    private lateinit var coordinator: SyncCoordinator
    private lateinit var runner: YouTubeSyncRunner
    private var now = 5_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = createInMemoryDatabase(context)
        sessions = FakeSessionStore()
        remote = FakeYouTubeRemote()
        meta = FakeMetaStore()
        coordinator = SyncCoordinator()
        val clock = { ++now }
        val engine = YouTubeSyncEngine(
            db, db.trackDao(), db.playlistDao(), db.subscriptionDao(), db.youTubeSyncDao(), remote, meta, clock,
        )
        runner = YouTubeSyncRunner(sessions, PendingActionProcessor(db.youTubeSyncDao(), remote, engine), engine, meta, coordinator, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun worker(mode: String) = TestListenableWorkerBuilder<YouTubeSyncWorker>(context)
        .setInputData(workDataOf(YouTubeSyncWorker.KEY_MODE to mode))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                YouTubeSyncWorker(appContext, workerParameters, runner)
        })
        .build()

    // region Worker

    @Test
    fun `synchro complete reussie - enregistre la derniere synchro et efface l erreur`() = runBlocking {
        remote.likedMusic += "a"
        meta.error.value = com.spautifaille.domain.youtube.SyncError("browse", "ancienne", 1)

        val result = worker(YouTubeSyncWorker.MODE_FULL).doWork()

        assertEquals(Result.success(), result)
        assertNotNull(meta.last.value)
        assertNull(meta.error.value)
        assertEquals(listOf("a"), db.youTubeSyncDao().trackIds(1))
    }

    @Test
    fun `sans compte connecte, le worker ne fait rien`() = runBlocking {
        sessions.stateFlow.value = AccountState.SignedOut

        val result = worker(YouTubeSyncWorker.MODE_FULL).doWork()

        assertEquals(Result.success(), result)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `erreur reseau - nouvel essai, sans afficher d erreur`() = runBlocking {
        remote.failures += "likedAll" to AppError.Network

        val result = worker(YouTubeSyncWorker.MODE_FULL).doWork()

        assertEquals(Result.retry(), result)
        assertNull(meta.error.value)
        assertNull(meta.last.value)
    }

    @Test
    fun `reconnexion necessaire - echec et compte marque, donnees locales conservees`() = runBlocking {
        LibraryRepositoryImpl(db, db.trackDao(), db.playlistDao(), db.historyDao(), db.subscriptionDao(), { ++now })
            .setLiked(track("a"), true)
        remote.failures += "likedAll" to AppError.YouTubeAuthRequired

        val result = worker(YouTubeSyncWorker.MODE_FULL).doWork()

        assertEquals(Result.failure(), result)
        assertTrue(sessions.stateFlow.value is AccountState.ReauthRequired)
        assertEquals(listOf("a"), db.youTubeSyncDao().trackIds(1))
    }

    @Test
    fun `erreur de structure - consignee pour le diagnostic sans nouvel essai`() = runBlocking {
        remote.failures += "subscriptions" to AppError.YouTubeSyncFailed("browse", "musicShelfRenderer introuvable")

        val result = worker(YouTubeSyncWorker.MODE_FULL).doWork()

        assertEquals(Result.success(), result)
        val error = meta.error.value!!
        assertEquals("browse", error.endpoint)
        assertTrue(error.message.contains("musicShelfRenderer introuvable"))
        assertTrue(error.message.contains("abonnements"))
        assertNull("la dernière synchro n'avance pas si une étape a échoué", meta.last.value)
    }

    @Test
    fun `mode file d actions - n execute pas la synchro complete`() = runBlocking {
        val library = LibraryRepositoryImpl(
            db, db.trackDao(), db.playlistDao(), db.historyDao(), db.subscriptionDao(), { ++now },
        )
        library.remoteSync = PendingActionRecorder(sessions, db.youTubeSyncDao(), com.spautifaille.data.youtube.FakeScheduler(), { ++now })
        library.setLiked(track("a"), true)

        val result = worker(YouTubeSyncWorker.MODE_FLUSH).doWork()

        assertEquals(Result.success(), result)
        assertEquals(setOf("a"), remote.likedMusic)
        assertTrue(remote.callsStartingWith("subscriptions").isEmpty())
        assertNull(meta.last.value)
    }

    @Test
    fun `le coordinateur allume l indicateur pendant une synchro complete seulement`() = runBlocking {
        var duringFull: Boolean? = null
        remote.hook = { call -> if (call == "likedAll") duringFull = coordinator.isSyncing.value }

        runner.fullSync()

        assertEquals(true, duringFull)
        assertEquals(false, coordinator.isSyncing.value)

        var duringFlush: Boolean? = null
        remote.hook = { duringFlush = coordinator.isSyncing.value }
        db.youTubeSyncDao().enqueue(ActionKind.LIKE, "x", true, 1)
        runner.flush()
        assertEquals(false, duringFlush)
    }

    // endregion

    // region Planification WorkManager

    @Test
    fun `le planificateur cree des travaux uniques avec contrainte reseau`() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val manager = WorkManager.getInstance(context)
        val scheduler = WorkManagerSyncScheduler(context)

        scheduler.scheduleFlush()
        scheduler.scheduleFlush()
        scheduler.requestFullSync()
        scheduler.requestFullSync()
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()

        val flush = manager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.FLUSH_WORK_NAME).get()
        // APPEND_OR_REPLACE : le second passage s'ajoute après le premier.
        assertTrue(flush.isNotEmpty())
        val now = manager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_NOW_WORK_NAME).get()
        assertEquals("KEEP : un seul travail", 1, now.size)
        val periodic = manager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(1, periodic.size)
        listOf(flush.first(), now.single(), periodic.single()).forEach {
            assertTrue(it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED)
        }

        scheduler.cancelAll()
        listOf(
            WorkManagerSyncScheduler.FLUSH_WORK_NAME,
            WorkManagerSyncScheduler.SYNC_NOW_WORK_NAME,
            WorkManagerSyncScheduler.PERIODIC_WORK_NAME,
        ).forEach { name ->
            assertTrue(manager.getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.CANCELLED })
        }
    }

    // endregion
}
