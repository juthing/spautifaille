package com.spautifaille.data.importer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Planification du traitement d'un job d'import (abstraction de WorkManager pour les tests). */
interface ImportScheduler {
    fun enqueue(jobId: Long)
    fun cancel(jobId: Long)
}

@Singleton
class WorkManagerImportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImportScheduler {

    // WorkManager n'est résolu qu'à l'usage : la configuration (HiltWorkerFactory) est fournie par :app.
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun enqueue(jobId: Long) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY_JOB_ID to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(ImportWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(jobId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(jobId: Long) {
        workManager.cancelUniqueWork(uniqueName(jobId))
    }

    companion object {
        private const val BACKOFF_SECONDS = 30L
        fun uniqueName(jobId: Long) = "import-$jobId"
    }
}
