package com.spautifaille.data.recommendation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.recommendation.Discovery
import com.spautifaille.domain.recommendation.DiscoveryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class DiscoveryRefreshWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeRepository(var failure: Throwable? = null) : DiscoveryRepository {
        var refreshes = 0
        override fun observe(): Flow<Discovery?> = emptyFlow()
        override suspend fun refresh() {
            refreshes++
            failure?.let { throw it }
        }
        override fun scheduleRefresh() = Unit
    }

    private fun worker(repository: DiscoveryRepository, attempt: Int = 0) =
        TestListenableWorkerBuilder<DiscoveryRefreshWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    DiscoveryRefreshWorker(appContext, workerParameters, repository)
            })
            .build()

    @Test
    fun `success refreshes once`() = runBlocking {
        val repo = FakeRepository()
        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())
        assertEquals(1, repo.refreshes)
    }

    @Test
    fun `recoverable errors retry then fail after the last attempt`() = runBlocking {
        val repo = FakeRepository(AppException(AppError.Network))
        assertEquals(ListenableWorker.Result.retry(), worker(repo, attempt = 0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(repo, attempt = 1).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(repo, attempt = DiscoveryRefreshWorker.MAX_ATTEMPTS - 1).doWork())
    }

    @Test
    fun `non recoverable errors fail without retry`() = runBlocking {
        val repo = FakeRepository(AppException(AppError.ExtractionBroken("x")))
        assertEquals(ListenableWorker.Result.failure(), worker(repo).doWork())
    }

    @Test
    fun `scheduling is unique periodic every 12 hours with network and battery constraints`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = DiscoveryRefreshScheduler(context)

        scheduler.schedule()
        scheduler.schedule()

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DiscoveryRefreshScheduler.WORK_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertTrue(info.constraints.requiredNetworkType == NetworkType.CONNECTED)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertEquals(12L * 60 * 60 * 1000, info.periodicityInfo!!.repeatIntervalMillis)
    }
}
