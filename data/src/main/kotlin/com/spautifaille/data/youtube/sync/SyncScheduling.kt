package com.spautifaille.data.youtube.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Planification des travaux de synchronisation (abstraite pour les tests des repositories). */
internal interface SyncScheduler {
    /** Rejoue la file d'actions dès que le réseau est disponible (backoff exponentiel en cas d'échec). */
    fun scheduleFlush()

    /** Synchronisation complète (file d'actions puis réconciliation) dès que le réseau est disponible. */
    fun requestFullSync(replace: Boolean = false)

    /** Synchronisation complète périodique (toutes les [PERIODIC_HOURS] h, réseau requis). Idempotent. */
    fun schedulePeriodic()

    /** Déconnexion : annule tout travail en attente. */
    fun cancelAll()

    companion object {
        const val PERIODIC_HOURS = 6L
    }
}

@Singleton
internal class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleFlush() {
        val request = OneTimeWorkRequestBuilder<YouTubeSyncWorker>()
            .setInputData(Data.Builder().putString(YouTubeSyncWorker.KEY_MODE, YouTubeSyncWorker.MODE_FLUSH).build())
            .setConstraints(connected)
            // Regroupe les basculements rapprochés (plusieurs likes d'affilée = un seul passage).
            .setInitialDelay(FLUSH_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE : un passage en cours n'est pas interrompu, le nouveau démarre après lui.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FLUSH_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun requestFullSync(replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<YouTubeSyncWorker>()
            .setInputData(Data.Builder().putString(YouTubeSyncWorker.KEY_MODE, YouTubeSyncWorker.MODE_FULL).build())
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC_NOW_WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<YouTubeSyncWorker>(SyncScheduler.PERIODIC_HOURS, TimeUnit.HOURS)
            .setInputData(Data.Builder().putString(YouTubeSyncWorker.KEY_MODE, YouTubeSyncWorker.MODE_FULL).build())
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(FLUSH_WORK_NAME)
        manager.cancelUniqueWork(SYNC_NOW_WORK_NAME)
        manager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    companion object {
        const val FLUSH_WORK_NAME = "youtube_flush"
        const val SYNC_NOW_WORK_NAME = "youtube_sync_now"
        const val PERIODIC_WORK_NAME = "youtube_sync_periodic"
        private const val FLUSH_DELAY_SECONDS = 3L
        private const val BACKOFF_SECONDS = 30L
    }
}

/**
 * Sérialise les passages de synchronisation (file d'actions, synchro complète, import / publication d'une playlist)
 * et expose l'indicateur « synchronisation en cours ».
 */
@Singleton
internal class SyncCoordinator @Inject constructor() {
    private val mutex = Mutex()
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    /** Exécute [block] en exclusion mutuelle ; [showProgress] allume l'indicateur le temps du passage. */
    suspend fun <T> exclusive(showProgress: Boolean, block: suspend () -> T): T = mutex.withLock {
        if (showProgress) _isSyncing.value = true
        try {
            block()
        } finally {
            if (showProgress) _isSyncing.value = false
        }
    }
}
