package com.spautifaille.data.importer

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportJobState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Le worker n'est qu'une coque autour d'[ImportProcessor] (testé à part) : on vérifie résultat, relance et notifications. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class ImportWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: ImportEnv

    @Before
    fun setUp() {
        env = ImportEnv()
    }

    @After
    fun tearDown() = env.close()

    private fun worker(jobId: Long?, attempt: Int = 0) = TestListenableWorkerBuilder<ImportWorker>(context)
        .setInputData(if (jobId == null) workDataOf() else workDataOf(ImportWorker.KEY_JOB_ID to jobId))
        .setRunAttemptCount(attempt)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    ImportWorker(appContext, workerParameters, env.processor(), env.store, env.db.importDao(), ImportNotifier(appContext))
            },
        )
        .build()

    @Test
    fun `worker completes the job, and posts the summary notification when allowed`() = runTest {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val (jobId, playlistId) = env.newJob(listOf(imported("Un", youtubeId = "a"), imported("Deux", youtubeId = "b")))
        env.stream.trackHandler = { ytTrack(it) }

        val result = worker(jobId).doWork()

        assertEquals(Result.success(), result)
        assertEquals(ImportJobState.COMPLETED.name, env.db.importDao().job(jobId)!!.state)
        assertEquals(listOf("a", "b"), env.playlistTrackIds(playlistId))
        val nm = context.getSystemService(NotificationManager::class.java)
        val texts = shadowOf(nm).allNotifications.map { it.extras.getCharSequence("android.text").toString() }
        assertTrue(texts.toString(), "Import terminé : 2 titres, 0 à vérifier, 0 introuvables" in texts)
        assertNotNull(nm.getNotificationChannel(ImportNotifier.CHANNEL_ID))
    }

    @Test
    fun `worker asks for a retry on network errors and fails for good after too many attempts`() = runTest {
        val (jobId, _) = env.newJob(listOf(imported("Un")))
        env.stream.searchHandler = { _, _ -> throw AppException(AppError.Network) }

        assertEquals(Result.retry(), worker(jobId, attempt = 0).doWork())
        assertEquals(ImportJobState.RUNNING.name, env.db.importDao().job(jobId)!!.state)

        assertEquals(Result.failure(), worker(jobId, attempt = 8).doWork())
        val job = env.db.importDao().job(jobId)!!
        assertEquals(ImportJobState.FAILED.name, job.state)
        assertNotNull(job.error)
    }

    @Test
    fun `worker ignores a deleted job and rejects missing input`() = runTest {
        assertEquals(Result.success(), worker(9999L).doWork())
        assertEquals(Result.failure(), worker(null).doWork())
    }
}
