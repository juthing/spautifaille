package com.spautifaille.data.download

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.spautifaille.data.local.DownloadDao
import com.spautifaille.data.local.TrackDao
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.domain.repository.StreamRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Télécharge un titre dans `filesDir/downloads/<id>.<ext>` (entrée : [DownloadWork.KEY_TRACK_ID]).
 *
 * - Résout le flux à chaque exécution (les URL expirent), sur HTTP 403 le résout à nouveau une fois et reprend
 *   à l'offset courant.
 * - Écrit dans `<id>.<ext>.part`, repris à sa taille après une interruption (réseau, contraintes, arrêt système).
 * - Erreurs définitives (indisponible, âge, pays, payant, pas de flux, DASH) : ligne FAILED + message français ;
 *   réseau / anti-bot : `Result.retry()` (backoff exponentiel), FAILED après [MAX_ATTEMPTS] essais en gardant le `.part`.
 * - Tourne en premier plan (`dataSync`) avec une notification de progression annulable.
 * - Au plus [DownloadLimiter.MAX_PARALLEL] téléchargements actifs à la fois.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val streamRepository: StreamRepository,
    private val settingsRepository: SettingsRepository,
    private val httpClient: OkHttpClient,
    private val limiter: DownloadLimiter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(context, params) {

    private val notifications = DownloadNotifications(applicationContext)
    private val downloadsDir: File get() = File(applicationContext.filesDir, DownloadFiles.DIRECTORY)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val trackId = inputData.getString(DownloadWork.KEY_TRACK_ID).orEmpty()
        return notifications.foregroundInfo(trackId, id, title = "Téléchargement", downloadedBytes = 0, totalBytes = null)
    }

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(DownloadWork.KEY_TRACK_ID) ?: return Result.failure()
        return try {
            limiter.withPermit { download(trackId) }
        } catch (e: CancellationException) {
            // Arrêt système (contraintes perdues, timeout du service) ou annulation : on rend la main proprement.
            withContext(NonCancellable) { onStopped(trackId) }
            throw e
        }
    }

    private suspend fun download(trackId: String): Result {
        val row = downloadDao.get(trackId) ?: return Result.success() // annulé / supprimé entre-temps
        if (row.state == DownloadState.COMPLETED.name && row.filePath?.let { File(it).exists() } == true) {
            return Result.success()
        }
        val title = trackDao.get(trackId)?.title ?: trackId
        promoteToForeground(trackId, title, row.downloadedBytes, row.totalBytes)
        downloadDao.updateProgress(trackId, row.downloadedBytes, row.totalBytes, System.currentTimeMillis())

        return try {
            perform(trackId, title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download of $trackId failed: ${e.message}", e)
            handleFailure(trackId, e)
        }
    }

    private suspend fun perform(trackId: String, title: String): Result {
        val quality = settingsRepository.current().audioQuality
        val stream = streamRepository.resolveAudio(trackId, quality)
        if (stream.dashManifest != null) return fail(trackId, DownloadMessages.NOT_DOWNLOADABLE, deleteParts = true)

        val extension = DownloadFiles.extensionFor(stream.mimeType)
        val dir = downloadsDir.apply { mkdirs() }
        val part = DownloadFiles.partFile(dir, trackId, extension)
        // Un `.part` d'un autre format (qualité changée entre deux essais) est inutilisable.
        DownloadFiles.partsOf(dir, trackId).filter { it != part }.forEach { it.delete() }

        val total = stream.contentLength ?: RangeUrl.contentLengthOf(stream.url)
        var offset = if (part.exists()) part.length() else 0L
        if (total != null && offset > total) {
            part.delete()
            offset = 0L
        }

        val startedAtNanos = System.nanoTime()
        var lastUpdateAt = -PROGRESS_INTERVAL_MS
        var lastNotificationAt = -NOTIFICATION_INTERVAL_MS
        val size = ChunkedDownloader(httpClient, io = io).download(
            source = stream.toSource(),
            sink = part,
            startOffset = offset,
            totalLength = total,
            onForbidden = { streamRepository.resolveAudio(trackId, quality).takeIf { it.dashManifest == null }?.toSource() },
            onProgress = { downloaded, totalBytes ->
                if (isStopped) throw CancellationException("Worker arrêté")
                val now = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI
                if (now - lastUpdateAt >= PROGRESS_INTERVAL_MS) {
                    lastUpdateAt = now
                    publishProgress(trackId, downloaded, totalBytes)
                    if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
                        lastNotificationAt = now
                        promoteToForeground(trackId, title, downloaded, totalBytes)
                    }
                }
            },
        )
        return complete(trackId, stream, part, extension, size)
    }

    /** Écrit la progression en base ; si la ligne a disparu (annulation), interrompt le téléchargement. */
    private suspend fun publishProgress(trackId: String, downloaded: Long, total: Long?) {
        val updated = downloadDao.updateProgress(trackId, downloaded, total, System.currentTimeMillis())
        if (updated == 0) throw CancellationException("Téléchargement supprimé")
        setProgress(
            workDataOf(
                DownloadWork.KEY_DOWNLOADED to downloaded,
                DownloadWork.KEY_TOTAL to (total ?: -1L),
            ),
        )
    }

    private suspend fun complete(
        trackId: String,
        stream: ResolvedStream,
        part: File,
        extension: String,
        size: Long,
    ): Result {
        val dir = part.parentFile ?: downloadsDir
        val finalFile = DownloadFiles.finalFile(dir, trackId, extension)
        Files.move(part.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val updated = downloadDao.markCompleted(
            trackId = trackId,
            filePath = finalFile.absolutePath,
            mimeType = stream.mimeType?.substringBefore(';')?.trim(),
            sizeBytes = size,
            now = System.currentTimeMillis(),
        )
        if (updated == 0) {
            // La ligne a été supprimée pendant le dernier bloc : rien ne doit rester sur le disque.
            DownloadFiles.deleteAllOf(dir, trackId)
        }
        return Result.success()
    }

    private suspend fun handleFailure(trackId: String, error: Exception): Result {
        if (error.isOutOfSpace()) return fail(trackId, DownloadMessages.NO_SPACE, deleteParts = false)
        val appError = error.toDownloadError()
        if (appError.isPermanent() || appError is AppError.ExtractionBroken || appError is AppError.Unknown) {
            return fail(trackId, DownloadMessages.forError(appError), deleteParts = true)
        }
        // Réseau / anti-bot / lien expiré : on réessaie avec backoff, en gardant le `.part`.
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            return fail(trackId, DownloadMessages.forError(appError), deleteParts = false)
        }
        downloadDao.changeState(trackId, DownloadState.RUNNING.name, DownloadState.QUEUED.name, System.currentTimeMillis())
        return Result.retry()
    }

    private suspend fun fail(trackId: String, message: String, deleteParts: Boolean): Result {
        if (deleteParts) DownloadFiles.partsOf(downloadsDir, trackId).forEach { it.delete() }
        downloadDao.markFailed(trackId, message, System.currentTimeMillis())
        return Result.failure()
    }

    /** Interruption : RUNNING -> QUEUED (le système relancera le worker) ; ligne supprimée -> plus aucun fichier. */
    private suspend fun onStopped(trackId: String) {
        val now = System.currentTimeMillis()
        downloadDao.changeState(trackId, DownloadState.RUNNING.name, DownloadState.QUEUED.name, now)
        if (downloadDao.get(trackId) == null) DownloadFiles.deleteAllOf(downloadsDir, trackId)
    }

    /** `setForeground` peut être refusé (démarrage en arrière-plan interdit) : le téléchargement continue sans. */
    private suspend fun promoteToForeground(trackId: String, title: String, downloaded: Long, total: Long?) {
        try {
            setForeground(notifications.foregroundInfo(trackId, id, title, downloaded, total))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground promotion refused for $trackId: ${e.message}")
        }
    }

    private fun ResolvedStream.toSource() = DownloadSource(url, headers)

    companion object {
        const val MAX_ATTEMPTS = 6
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val TAG = "DownloadWorker"
    }
}
