package com.spautifaille.data.youtube.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spautifaille.data.youtube.session.SyncMetaStore
import com.spautifaille.data.youtube.session.YouTubeSessionStore
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.SyncError
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Issue d'un passage de synchronisation, traduite en `Result` WorkManager par [YouTubeSyncWorker]. */
internal enum class RunOutcome { SUCCESS, RETRY, FAILURE }

/**
 * Orchestre un passage : file d'actions seule ([flush]) ou synchronisation complète ([fullSync]), avec consignation
 * de la dernière synchro / erreur pour le mode diagnostic. Les erreurs réseau et de throttling ne sont pas
 * affichées comme erreurs (nouvel essai automatique) ; « reconnexion nécessaire » met le compte en attente de
 * reconnexion sans toucher aux données locales.
 */
@Singleton
internal class YouTubeSyncRunner internal constructor(
    private val sessions: YouTubeSessionStore,
    private val processor: PendingActionProcessor,
    private val engine: YouTubeSyncEngine,
    private val meta: SyncMetaStore,
    private val coordinator: SyncCoordinator,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        sessions: YouTubeSessionStore,
        processor: PendingActionProcessor,
        engine: YouTubeSyncEngine,
        meta: SyncMetaStore,
        coordinator: SyncCoordinator,
    ) : this(sessions, processor, engine, meta, coordinator, System::currentTimeMillis)

    suspend fun flush(): RunOutcome = execute(full = false)

    suspend fun fullSync(): RunOutcome = execute(full = true)

    private suspend fun execute(full: Boolean): RunOutcome {
        if (sessions.state.first() !is AccountState.SignedIn) return RunOutcome.SUCCESS
        return coordinator.exclusive(showProgress = full) {
            try {
                val failures = buildList {
                    addAll(processor.flush())
                    if (full) addAll(engine.syncAll())
                }
                if (full) {
                    if (failures.isEmpty()) {
                        meta.setLastSyncAt(clock())
                        meta.setLastError(null)
                    } else {
                        meta.setLastError(failures.first().toSyncError(clock()))
                    }
                } else if (failures.isNotEmpty()) {
                    meta.setLastError(failures.first().toSyncError(clock()))
                }
                // Une erreur non récupérable n'est pas rejouée en boucle : la synchro périodique réessaiera.
                RunOutcome.SUCCESS
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                when {
                    e.error == AppError.YouTubeAuthRequired -> {
                        sessions.markReauthRequired()
                        RunOutcome.FAILURE
                    }
                    e.error.isRecoverable -> RunOutcome.RETRY
                    else -> {
                        meta.setLastError(StepFailure("synchronisation", e.error).toSyncError(clock()))
                        RunOutcome.FAILURE
                    }
                }
            } catch (e: Exception) {
                meta.setLastError(StepFailure("synchronisation", AppError.Unknown(e.message)).toSyncError(clock()))
                RunOutcome.FAILURE
            }
        }
    }
}

/** Erreur lisible dans le mode diagnostic : endpoint concerné + message technique (jamais de cookie). */
internal fun StepFailure.toSyncError(atMillis: Long): SyncError = when (val e = error) {
    is AppError.YouTubeSyncFailed -> SyncError(e.endpoint, "$step : ${e.detail ?: "échec sans détail"}", atMillis)
    AppError.YouTubeAuthRequired -> SyncError("auth", "$step : session refusée par YouTube, reconnexion nécessaire", atMillis)
    AppError.BotDetected -> SyncError("http", "$step : HTTP 429, trop de requêtes", atMillis)
    AppError.Network -> SyncError("réseau", "$step : réseau indisponible", atMillis)
    else -> SyncError(step, "$step : $e", atMillis)
}

@HiltWorker
internal class YouTubeSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: YouTubeSyncRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = if (inputData.getString(KEY_MODE) == MODE_FULL) runner.fullSync() else runner.flush()
        return when (outcome) {
            RunOutcome.SUCCESS -> Result.success()
            RunOutcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
            RunOutcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_FLUSH = "flush"
        const val MODE_FULL = "full"
        const val MAX_ATTEMPTS = 6
    }
}
