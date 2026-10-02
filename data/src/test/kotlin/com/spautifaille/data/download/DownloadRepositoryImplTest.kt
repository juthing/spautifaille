package com.spautifaille.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.spautifaille.data.local.DownloadEntity
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.toEntity
import com.spautifaille.data.local.track
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class DownloadRepositoryImplTest {

    private class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun current(): AppSettings = state.value
        override suspend fun setAudioQuality(quality: AudioQuality) = Unit
        override suspend fun setDownloadOverWifiOnly(enabled: Boolean) {
            state.value = state.value.copy(downloadOverWifiOnly = enabled)
        }
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setColorSource(source: ColorSource) = Unit
        override suspend fun setStreamCacheSizeMb(sizeMb: Int) = Unit
        override suspend fun setLastFmApiKey(key: String?) = Unit
    }

    private lateinit var context: Context
    private lateinit var db: SpautifailleDatabase
    private lateinit var workManager: WorkManager
    private lateinit var settings: FakeSettings
    private lateinit var scope: CoroutineScope
    private lateinit var repo: DownloadRepositoryImpl

    private val downloadsDir get() = File(context.filesDir, "downloads")
    private val playerCacheDir get() = File(context.cacheDir, "media")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        db = createInMemoryDatabase(context)
        settings = FakeSettings()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        downloadsDir.deleteRecursively()
        playerCacheDir.deleteRecursively()
        repo = newRepository(scope)
    }

    @After
    fun tearDown() {
        // Les coroutines d'entretien doivent être terminées avant de fermer la base.
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        db.close()
    }

    private fun newRepository(scope: CoroutineScope) = DownloadRepositoryImpl(
        context = context,
        downloadDao = db.downloadDao(),
        trackDao = db.trackDao(),
        workManager = workManager,
        settingsRepository = settings,
        scope = scope,
        io = Dispatchers.IO,
    )

    /** Le flux partagé rejoue le dernier instantané, éventuellement antérieur aux lignes insérées par le test. */
    private suspend fun ReceiveTurbine<List<Download>>.awaitNonEmpty(): List<Download> {
        var item = awaitItem()
        while (item.isEmpty()) item = awaitItem()
        return item
    }

    private fun workInfos(trackId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork("download-$trackId").get()

    private fun awaitUntil(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Délai dépassé : $message" }
            Thread.sleep(20)
        }
    }

    /** Insère directement une ligne de téléchargement (avec son titre). */
    private suspend fun seed(
        id: String,
        state: DownloadState,
        downloaded: Long = 0,
        total: Long? = null,
        filePath: String? = null,
        error: String? = null,
    ) {
        db.trackDao().upsertAll(listOf(track(id).toEntity(updatedAt = 1)))
        db.downloadDao().upsert(
            DownloadEntity(id, state.name, downloaded, total, filePath, null, error, createdAt = 10, updatedAt = 10),
        )
    }

    private fun completedFile(id: String, bytes: Int = 100): File {
        downloadsDir.mkdirs()
        return File(downloadsDir, "$id.m4a").apply { writeBytes(ByteArray(bytes) { 1 }) }
    }

    // region enqueue

    @Test
    fun enqueueUpsertsTracksAndSchedulesOneTaggedWorkPerTrack() = runTest {
        repo.enqueue(listOf(track("a"), track("b")))

        assertEquals("Titre a", db.trackDao().get("a")?.title)
        for (id in listOf("a", "b")) {
            val row = db.downloadDao().get(id)!!
            assertEquals(DownloadState.QUEUED.name, row.state)
            val infos = workInfos(id)
            assertEquals(1, infos.size)
            assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
            assertTrue("downloads" in infos.single().tags)
        }
    }

    @Test
    fun enqueueSkipsCompletedQueuedAndRunningButRequeuesFailed() = runTest {
        val file = completedFile("done")
        seed("done", DownloadState.COMPLETED, 100, 100, file.absolutePath)
        seed("queued", DownloadState.QUEUED)
        seed("running", DownloadState.RUNNING, 50, 200)
        seed("failed", DownloadState.FAILED, 30, 200, error = "Connexion impossible")

        repo.enqueue(listOf(track("done"), track("queued"), track("running"), track("failed"), track("fresh"), track("fresh")))

        assertTrue(workInfos("done").isEmpty())
        assertTrue(workInfos("queued").isEmpty())
        assertTrue(workInfos("running").isEmpty())
        assertEquals(1, workInfos("failed").size)
        assertEquals(1, workInfos("fresh").size) // doublon dans la liste ignoré
        val failed = db.downloadDao().get("failed")!!
        assertEquals(DownloadState.QUEUED.name, failed.state)
        assertNull(failed.error)
        assertEquals(30, failed.downloadedBytes) // le `.part` déjà reçu sera repris
        assertEquals(DownloadState.COMPLETED.name, db.downloadDao().get("done")!!.state)
    }

    @Test
    fun enqueueRedownloadsACompletedTrackWhoseFileVanished() = runTest {
        seed("gone", DownloadState.COMPLETED, 100, 100, File(downloadsDir, "gone.m4a").absolutePath)

        repo.enqueue(listOf(track("gone")))

        assertEquals(DownloadState.QUEUED.name, db.downloadDao().get("gone")!!.state)
        assertEquals(1, workInfos("gone").size)
    }

    @Test
    fun enqueueTwiceKeepsASingleWork() = runTest {
        repo.enqueue(listOf(track("a")))
        repo.enqueue(listOf(track("a")))

        assertEquals(1, workInfos("a").size)
    }

    // endregion

    // region Wi-Fi

    @Test
    fun wifiOffUsesAnyConnectedNetwork() = runTest {
        settings.state.value = AppSettings(downloadOverWifiOnly = false)

        repo.enqueue(listOf(track("mobile")))

        val constraints = workInfos("mobile").single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun wifiOnlyUsesUnmeteredNetwork() = runTest {
        settings.state.value = AppSettings(downloadOverWifiOnly = true)

        repo.enqueue(listOf(track("a")))

        val constraints = workInfos("a").single().constraints
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun changingWifiSettingReappliesConstraintToPendingWorks() = runTest {
        settings.state.value = AppSettings(downloadOverWifiOnly = false)
        repo.enqueue(listOf(track("a"), track("b")))
        assertEquals(NetworkType.CONNECTED, workInfos("a").single().constraints.requiredNetworkType)

        settings.state.value = AppSettings(downloadOverWifiOnly = true)

        awaitUntil(message = "contrainte UNMETERED") {
            listOf("a", "b").all { workInfos(it).singleOrNull()?.constraints?.requiredNetworkType == NetworkType.UNMETERED }
        }
        // Toujours un seul travail par titre : mise à jour, pas d'empilement.
        assertEquals(1, workInfos("a").size)
    }

    // endregion

    // region cancel / retry / delete

    @Test
    fun cancelRemovesRowPartialFileAndWork() = runTest {
        repo.enqueue(listOf(track("a")))
        downloadsDir.mkdirs()
        val part = File(downloadsDir, "a.m4a.part").apply { writeBytes(ByteArray(64)) }

        repo.cancel("a")

        assertNull(db.downloadDao().get("a"))
        assertFalse(part.exists())
        assertEquals(WorkInfo.State.CANCELLED, workInfos("a").single().state)
    }

    @Test
    fun cancelDoesNotTouchACompletedDownload() = runTest {
        val file = completedFile("done")
        seed("done", DownloadState.COMPLETED, 100, 100, file.absolutePath)

        repo.cancel("done")

        assertTrue(file.exists())
        assertNotNull(db.downloadDao().get("done"))
    }

    @Test
    fun deleteRemovesCompletedFileAndRow() = runTest {
        val file = completedFile("done")
        seed("done", DownloadState.COMPLETED, 100, 100, file.absolutePath)
        seed("other", DownloadState.COMPLETED, 100, 100, completedFile("other").absolutePath)
        repo.localFileBlocking("done")

        repo.delete("done")

        assertFalse(file.exists())
        assertNull(db.downloadDao().get("done"))
        assertNull(repo.localFileBlocking("done"))
        assertTrue(File(downloadsDir, "other.m4a").exists())
        assertNotNull(db.downloadDao().get("other"))
    }

    @Test
    fun deleteAllClearsRowsFilesAndWorks() = runTest {
        repo.enqueue(listOf(track("a")))
        seed("done", DownloadState.COMPLETED, 100, 100, completedFile("done").absolutePath)
        File(downloadsDir, "a.webm.part").writeBytes(ByteArray(10))

        repo.deleteAll()

        assertTrue(db.downloadDao().all().isEmpty())
        assertTrue(downloadsDir.listFiles().orEmpty().isEmpty())
        assertEquals(WorkInfo.State.CANCELLED, workInfos("a").single().state)
        assertNull(repo.localFileBlocking("done"))
    }

    @Test
    fun retryRequeuesOnlyFailedDownloads() = runTest {
        seed("failed", DownloadState.FAILED, 10, 100, error = "Connexion impossible")
        seed("done", DownloadState.COMPLETED, 100, 100, completedFile("done").absolutePath)

        repo.retry("failed")
        repo.retry("done")
        repo.retry("unknown")

        val failed = db.downloadDao().get("failed")!!
        assertEquals(DownloadState.QUEUED.name, failed.state)
        assertNull(failed.error)
        assertEquals(1, workInfos("failed").size)
        assertTrue(workInfos("done").isEmpty())
        assertNull(db.downloadDao().get("unknown"))
    }

    @Test
    fun cancelFromTheNotificationActionDeletesTheDownload() = runTest {
        repo.enqueue(listOf(track("a"), track("b")))
        downloadsDir.mkdirs()
        val part = File(downloadsDir, "a.m4a.part").apply { writeBytes(ByteArray(8)) }

        // L'action « Annuler » de la notification passe par WorkManager, pas par le repository.
        workManager.cancelWorkById(workInfos("a").single().id).result.get()

        awaitUntil(message = "suppression après annulation") { rowOf("a") == null && !part.exists() }
        assertNotNull(rowOf("b"))
    }

    private fun rowOf(id: String) = kotlinx.coroutines.runBlocking { db.downloadDao().get(id) }

    @Test
    fun startupReconciliationSchedulesWorkForOrphanedPendingRows() = runTest {
        seed("orphan", DownloadState.RUNNING, 10, 100)
        assertTrue(workInfos("orphan").isEmpty())

        val other = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            newRepository(other)
            awaitUntil(message = "travail recréé") { workInfos("orphan").size == 1 }
        } finally {
            runBlocking { other.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    // endregion

    // region localFileBlocking

    @Test
    fun localFileBlockingReturnsPathOfCompletedFilesOnly() = runTest {
        val file = completedFile("done")
        seed("done", DownloadState.COMPLETED, 100, 100, file.absolutePath)
        seed("queued", DownloadState.QUEUED)
        seed("failed", DownloadState.FAILED)

        awaitUntil(message = "index") { repo.localFileBlocking("done") == file.absolutePath }
        assertNull(repo.localFileBlocking("queued"))
        assertNull(repo.localFileBlocking("failed"))
        assertNull(repo.localFileBlocking("missing"))
    }

    @Test
    fun localFileBlockingFallsBackToTheDatabaseWhenTheIndexIsNotLoaded() = runTest {
        val file = completedFile("done")
        seed("done", DownloadState.COMPLETED, 100, 100, file.absolutePath)
        // Scope déjà annulé : les coroutines d'index ne tournent jamais.
        val dead = CoroutineScope(Job().apply { cancel() })

        val cold = newRepository(dead)

        assertEquals(file.absolutePath, cold.localFileBlocking("done"))
    }

    @Test
    fun localFileBlockingIndexFollowsTheTable() = runTest {
        val file = completedFile("a")
        seed("a", DownloadState.COMPLETED, 100, 100, file.absolutePath)
        awaitUntil(message = "index chargé") { repo.localFileBlocking("a") != null }

        db.downloadDao().delete("a")

        awaitUntil(message = "index vidé") { repo.localFileBlocking("a") == null }
    }

    @Test
    fun localFileBlockingReturnsNullAndFailsTheRowWhenTheFileIsGone() = runTest {
        val file = completedFile("a")
        seed("a", DownloadState.COMPLETED, 100, 100, file.absolutePath)
        awaitUntil(message = "index chargé") { repo.localFileBlocking("a") != null }
        assertTrue(file.delete())

        assertNull(repo.localFileBlocking("a"))

        awaitUntil(message = "ligne FAILED") { rowOf("a")?.state == DownloadState.FAILED.name }
        assertEquals("Fichier introuvable", rowOf("a")?.error)
        assertNull(repo.localFileBlocking("a"))
    }

    // endregion

    // region observation

    @Test
    fun observeDownloadsMapsProgress() = runTest {
        seed("running", DownloadState.RUNNING, downloaded = 25, total = 100)
        seed("unknown", DownloadState.RUNNING, downloaded = 25, total = null)
        seed("done", DownloadState.COMPLETED, 100, 100, completedFile("done").absolutePath)

        // Flux partagé (avec rejeu du dernier instantané) : on attend l'instantané contenant les trois lignes.
        val downloads = repo.observeDownloads().first { it.size == 3 }.associateBy { it.track.id }

        assertEquals(0.25f, downloads.getValue("running").progress!!, 0.0001f)
        assertNull(downloads.getValue("unknown").progress)
        assertEquals(1f, downloads.getValue("done").progress!!, 0.0001f)
        assertEquals(DownloadState.RUNNING, downloads.getValue("running").state)
        assertEquals("Titre running", downloads.getValue("running").track.title)
        assertEquals(100L, downloads.getValue("running").totalBytes)
    }

    @Test
    fun observeDownloadEmitsNullThenTheRow() = runTest {
        repo.observeDownload("a").test {
            assertNull(awaitItem())
            seed("a", DownloadState.QUEUED)
            assertEquals(DownloadState.QUEUED, awaitItem()?.state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun storageUsageSumsDownloadsDirCacheDirAndCompletedCount() = runTest {
        // Tous les fichiers existent avant la première ligne : le flux partagé peut émettre à tout moment.
        val fileA = completedFile("a", 100)
        val fileB = completedFile("b", 50)
        File(downloadsDir, "c.m4a.part").writeBytes(ByteArray(10))
        playerCacheDir.mkdirs()
        File(playerCacheDir, "0.v3.exo").writeBytes(ByteArray(300))
        File(playerCacheDir, "sub").mkdirs()
        File(playerCacheDir, "sub/1.v3.exo").writeBytes(ByteArray(20))
        seed("a", DownloadState.COMPLETED, 100, 100, fileA.absolutePath)
        seed("b", DownloadState.COMPLETED, 50, 50, fileB.absolutePath)
        seed("c", DownloadState.RUNNING, 10, 100)

        val usage = repo.observeStorageUsage().first { it.downloadCount == 2 }

        assertEquals(StorageUsage(downloadsBytes = 160, cacheBytes = 320, downloadCount = 2), usage)
    }

    @Test
    fun storageUsageIsZeroWhenNothingExists() = runTest {
        assertEquals(StorageUsage(0, 0, 0), repo.observeStorageUsage().first())
    }

    @Test
    fun observeDownloadsDoesNotReEmitWhenOnlyHiddenColumnsChange() = runTest {
        seed("running", DownloadState.RUNNING, downloaded = 25, total = 100)
        repo.observeDownloads().test {
            assertEquals(0.25f, awaitNonEmpty().single().progress!!, 0.0001f)

            // Même octets, même état : seul `updated_at` change -> aucune réémission.
            db.downloadDao().updateProgress("running", 25, 100, now = 5_000)
            db.downloadDao().updateProgress("running", 50, 100, now = 5_001)

            // Le premier élément reçu est déjà celui à 50 % : la mise à jour intermédiaire a été filtrée.
            assertEquals(0.5f, awaitItem().single().progress!!, 0.0001f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun severalCollectorsShareTheSameSnapshots() = runTest {
        seed("a", DownloadState.QUEUED)
        val first = repo.observeDownloads()
        val second = repo.observeDownloads()

        first.test {
            assertEquals(listOf("a"), awaitNonEmpty().map { it.track.id })
            second.test {
                assertEquals(listOf("a"), awaitNonEmpty().map { it.track.id })
                seed("b", DownloadState.QUEUED)
                assertEquals(setOf("a", "b"), awaitItem().map { it.track.id }.toSet())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(setOf("a", "b"), awaitItem().map { it.track.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion
}
