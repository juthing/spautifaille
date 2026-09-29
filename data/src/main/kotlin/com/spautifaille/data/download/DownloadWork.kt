package com.spautifaille.data.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.spautifaille.data.local.DownloadEntity
import com.spautifaille.data.local.DownloadWithTrack
import com.spautifaille.data.local.toDomain
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Noms, tags et construction des requêtes WorkManager des téléchargements. */
internal object DownloadWork {
    /** Tag commun à tous les téléchargements : ils forment « la file » (annulation globale, observation). */
    const val TAG_ALL = "downloads"

    const val KEY_TRACK_ID = "track_id"
    const val KEY_DOWNLOADED = "downloaded_bytes"
    const val KEY_TOTAL = "total_bytes"

    private const val TAG_PREFIX = "download-"
    private const val BACKOFF_SECONDS = 30L

    /** Nom du travail unique ET tag propre au titre. */
    fun uniqueName(trackId: String) = "$TAG_PREFIX$trackId"

    fun trackIdOf(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)

    fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    /** [existingId] : identifiant du travail à mettre à jour (`WorkManager.updateWork`), sinon un nouveau travail. */
    fun request(trackId: String, wifiOnly: Boolean, existingId: UUID? = null): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .apply { if (existingId != null) setId(existingId) }
            .setInputData(workDataOf(KEY_TRACK_ID to trackId))
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_ALL)
            .addTag(uniqueName(trackId))
            .build()
}

/**
 * Limite le nombre de téléchargements simultanés (YouTube bride les rafales). WorkManager exécute tous les
 * workers éligibles en parallèle ; les workers excédentaires attendent ici, sans notification ni octet lu,
 * dans l'ordre d'arrivée (sémaphore équitable), et restent `QUEUED` en base.
 */
@Singleton
class DownloadLimiter(permits: Int) {
    @Inject constructor() : this(MAX_PARALLEL)

    private val semaphore = Semaphore(permits)

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }

    companion object {
        const val MAX_PARALLEL = 2
    }
}

internal fun DownloadWithTrack.toDomain(): Download = Download(
    track = track.toDomain(),
    state = download.domainState(),
    progress = download.progress(),
    downloadedBytes = download.downloadedBytes,
    totalBytes = download.totalBytes,
    filePath = download.filePath,
    error = download.error,
    createdAt = download.createdAt,
)

private fun DownloadEntity.domainState(): DownloadState =
    DownloadState.entries.firstOrNull { it.name == state } ?: DownloadState.FAILED

private fun DownloadEntity.progress(): Float? = when {
    state == DownloadState.COMPLETED.name -> 1f
    totalBytes != null && totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    else -> null
}
