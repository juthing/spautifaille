package com.spautifaille.data.recommendation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.recommendation.DiscoveryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Recalcule la découverte en arrière-plan. Réessaie (backoff exponentiel) sur les erreurs récupérables
 * (réseau, throttling) jusqu'à [MAX_ATTEMPTS] tentatives ; la prochaine période reste planifiée quoi qu'il arrive.
 */
@HiltWorker
class DiscoveryRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: DiscoveryRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        repository.refresh()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val recoverable = (e as? AppException)?.error?.isRecoverable == true
        if (recoverable && runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/** Programme le [DiscoveryRefreshWorker] : toutes les 12 h, réseau connecté et batterie non faible. */
@Singleton
class DiscoveryRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Idempotent : `KEEP` conserve la planification existante. */
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<DiscoveryRefreshWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            // L'écran d'accueil rafraîchit déjà à l'ouverture si le cache est périmé : premier passage du worker dans 12 h.
            .setInitialDelay(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "discovery_refresh"
        const val REPEAT_INTERVAL_HOURS = 12L
        private const val BACKOFF_MINUTES = 15L
    }
}
