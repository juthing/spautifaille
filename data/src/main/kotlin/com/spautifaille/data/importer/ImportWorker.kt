package com.spautifaille.data.importer

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.spautifaille.data.R
import com.spautifaille.data.local.ImportDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Worker d'un job d'import : délègue la boucle à [ImportProcessor] et gère notification de premier plan,
 * progression et politique de relance. Nécessite `HiltWorkerFactory` dans la `Configuration` de WorkManager (`:app`).
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: ImportProcessor,
    private val store: ImportStore,
    private val importDao: ImportDao,
    private val notifier: ImportNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.foregroundInfo(jobId(), importDao.job(jobId()))

    override suspend fun doWork(): Result {
        val jobId = jobId()
        if (jobId < 0) return Result.failure()
        if (importDao.job(jobId) == null) return Result.success() // supprimé avant le démarrage

        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Démarrage de service de premier plan refusé (app en arrière-plan) : on continue sans.
            Log.w(TAG, "Foreground service unavailable", e)
        }

        val outcome = processor.process(jobId) { job ->
            setProgress(workDataOf(KEY_PROCESSED to job.processed, KEY_TOTAL to job.total))
            notifier.updateProgress(jobId, job)
        }
        return when (outcome) {
            ImportOutcome.Done -> {
                importDao.job(jobId)?.let { notifier.showCompleted(it) }
                Result.success()
            }
            is ImportOutcome.Retry ->
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    store.fail(jobId, applicationContext.getString(R.string.data_import_error_interrupted))
                    Result.failure()
                } else {
                    Result.retry()
                }
        }
    }

    private fun jobId(): Long = inputData.getLong(KEY_JOB_ID, -1L)

    companion object {
        const val TAG = "import"
        const val KEY_JOB_ID = "jobId"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        private const val MAX_ATTEMPTS = 8
    }
}
