package com.spautifaille.data.download

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.spautifaille.data.local.DownloadDao
import com.spautifaille.data.local.DownloadEntity
import com.spautifaille.data.local.DownloadWithTrack
import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.toEntity
import com.spautifaille.domain.di.ApplicationScope
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Téléchargements : état en base (`downloads`), exécution par [DownloadWorker] (WorkManager, un travail unique
 * `download-<id>` par titre), fichiers dans `filesDir/downloads`.
 *
 * Concurrence : WorkManager lance tous les travaux éligibles ; [DownloadLimiter] n'en laisse progresser que 2 à la
 * fois (les autres restent QUEUED). Réglage « Wi-Fi uniquement » : contrainte `UNMETERED` sinon `CONNECTED`,
 * réappliquée aux travaux en attente à chaque changement du réglage (`ExistingWorkPolicy.UPDATE` : un travail déjà
 * en cours n'est pas interrompu, la nouvelle contrainte vaut dès sa prochaine reprise).
 *
 * L'instance démarre ses coroutines d'entretien à la construction (index des fichiers, réconciliation, réglage) :
 * l'injecter au démarrage de l'application (ex. `Application.onCreate`) les rend actives dès le lancement.
 */
@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DownloadRepository {

    private val messages = DownloadMessages(context)
    private val downloadsDir: File get() = File(context.filesDir, DownloadFiles.DIRECTORY)
    private val playerCacheDir: File get() = File(context.cacheDir, DownloadFiles.PLAYER_CACHE_DIRECTORY)

    /** trackId -> chemin absolu, pour les téléchargements COMPLETED uniquement. */
    private val completedFiles = ConcurrentHashMap<String, String>()

    /**
     * Unique observateur Room de la table, partagé par tous les collecteurs (écran Téléchargements, feuilles d'actions,
     * stockage, index des fichiers). Chaque mise à jour de progression réécrit la ligne : la liste du domaine est
     * dédoublonnée pour ne rien réémettre quand seul un champ non exposé (`updated_at`) change.
     */
    private val sharedDownloads: SharedFlow<List<Download>> = downloadDao.observeAll()
        .map { rows -> rows.map(DownloadWithTrack::toDomain) }
        .distinctUntilChanged()
        .catch { Log.w(TAG, "Downloads observer failed", it) }
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)

    init {
        launchGuarded("index") { keepIndexInSync() }
        launchGuarded("maintenance") { reconcileThenWatchCancellations() }
        launchGuarded("wifi") { reapplyNetworkConstraintOnSettingChange() }
    }

    /** Une panne d'entretien ne doit jamais faire tomber le process (scope applicatif sans handler). */
    private fun launchGuarded(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download $name job failed", e)
            }
        }
    }

    // region Observation

    override fun observeDownloads(): Flow<List<Download>> = sharedDownloads

    override fun observeDownload(trackId: String): Flow<Download?> =
        sharedDownloads.map { downloads -> downloads.firstOrNull { it.track.id == trackId } }.distinctUntilChanged()

    override fun observeStorageUsage(): Flow<StorageUsage> =
        sharedDownloads
            // Recalcul quand un titre change d'état ou grossit d'environ un Mo (pas à chaque mise à jour de progression).
            .map { downloads -> downloads.map { Triple(it.track.id, it.state, it.downloadedBytes shr 20) } }
            .distinctUntilChanged()
            .map { keys ->
                withContext(io) {
                    StorageUsage(
                        downloadsBytes = DownloadFiles.sizeOf(downloadsDir),
                        cacheBytes = DownloadFiles.sizeOf(playerCacheDir),
                        downloadCount = keys.count { it.second == DownloadState.COMPLETED },
                    )
                }
            }

    // endregion

    // region Commandes

    override suspend fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val unique = tracks.distinctBy { it.id }
        val now = System.currentTimeMillis()
        trackDao.upsertAll(unique.map { it.toEntity(updatedAt = now) })
        val wifiOnly = settingsRepository.current().downloadOverWifiOnly
        for (track in unique) {
            val existing = downloadDao.get(track.id)
            if (existing != null && existing.isActiveOrDone()) continue
            downloadDao.upsert(
                DownloadEntity(
                    trackId = track.id,
                    state = DownloadState.QUEUED.name,
                    // Un `.part` conservé après un échec est repris : on garde sa progression.
                    downloadedBytes = existing?.downloadedBytes ?: 0L,
                    totalBytes = existing?.totalBytes,
                    filePath = null,
                    mimeType = null,
                    error = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            schedule(track.id, wifiOnly, ExistingWorkPolicy.KEEP)
        }
    }

    override suspend fun cancel(trackId: String) {
        val row = downloadDao.get(trackId) ?: return
        // Terminé : rien à annuler (voir [delete]).
        if (row.state == DownloadState.COMPLETED.name && row.filePath?.let { File(it).exists() } == true) return
        remove(trackId)
    }

    override suspend fun retry(trackId: String) {
        val row = downloadDao.get(trackId) ?: return
        if (row.state != DownloadState.FAILED.name) return
        downloadDao.requeue(trackId, System.currentTimeMillis())
        schedule(trackId, settingsRepository.current().downloadOverWifiOnly, ExistingWorkPolicy.KEEP)
    }

    override suspend fun delete(trackId: String) = remove(trackId)

    override suspend fun deleteAll() {
        workManager.cancelAllWorkByTag(DownloadWork.TAG_ALL)
        downloadDao.deleteAll()
        completedFiles.clear()
        withContext(io) { downloadsDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    private suspend fun remove(trackId: String) {
        // Ligne d'abord : le worker qui écrit encore ses dernières données ne la ressuscite pas (UPDATE seulement).
        downloadDao.delete(trackId)
        completedFiles.remove(trackId)
        workManager.cancelUniqueWork(DownloadWork.uniqueName(trackId))
        withContext(io) { DownloadFiles.deleteAllOf(downloadsDir, trackId) }
    }

    // endregion

    // region Accès du lecteur

    override fun localFileBlocking(trackId: String): String? {
        val indexed = completedFiles[trackId]
        val path = indexed ?: downloadDao.completedFilePathBlocking(trackId)?.also { completedFiles[trackId] = it }
            ?: return null
        if (File(path).isFile) return path
        // Fichier disparu (effacé hors de l'app, stockage nettoyé) : le titre redevient à télécharger.
        completedFiles.remove(trackId, path)
        launchGuarded("mark-missing") {
            downloadDao.markFailed(trackId, messages.fileMissing, System.currentTimeMillis())
        }
        return null
    }

    private suspend fun keepIndexInSync() {
        sharedDownloads.collect { downloads ->
            val completed = downloads.asSequence()
                .filter { it.state == DownloadState.COMPLETED && it.filePath != null }
                .associate { it.track.id to it.filePath!! }
            completedFiles.keys.retainAll(completed.keys)
            completedFiles.putAll(completed)
        }
    }

    // endregion

    // region Entretien

    /**
     * Au démarrage : tout titre QUEUED/RUNNING doit avoir un travail actif (perdu après un effacement des données de
     * WorkManager, par exemple). Ensuite, un travail annulé depuis la notification (action « Annuler », qui passe
     * directement par WorkManager) supprime le téléchargement correspondant.
     */
    private suspend fun reconcileThenWatchCancellations() {
        val wifiOnly = settingsRepository.current().downloadOverWifiOnly
        for (row in downloadDao.pending()) {
            val infos = workManager.getWorkInfosForUniqueWorkFlow(DownloadWork.uniqueName(row.trackId)).first()
            if (infos.none { !it.state.isFinished }) schedule(row.trackId, wifiOnly, ExistingWorkPolicy.KEEP)
        }
        workManager.getWorkInfosByTagFlow(DownloadWork.TAG_ALL).collect { infos ->
            infos.groupBy { DownloadWork.trackIdOf(it) }.forEach { (trackId, group) ->
                if (trackId == null) return@forEach
                val cancelledByUser = group.none { !it.state.isFinished } && group.any { it.state == WorkInfo.State.CANCELLED }
                if (cancelledByUser && downloadDao.get(trackId)?.state in PENDING_STATES) remove(trackId)
            }
        }
    }

    private suspend fun reapplyNetworkConstraintOnSettingChange() {
        settingsRepository.settings
            .map { it.downloadOverWifiOnly }
            .distinctUntilChanged()
            .drop(1)
            .collect { wifiOnly ->
                downloadDao.pending().forEach { reapplyConstraint(it.trackId, wifiOnly) }
            }
    }

    /**
     * Change la contrainte réseau du travail en attente de [trackId] sans l'annuler (`updateWork`) ; s'il n'y en
     * a aucun, en crée un.
     */
    private suspend fun reapplyConstraint(trackId: String, wifiOnly: Boolean) {
        val infos = workManager.getWorkInfosForUniqueWorkFlow(DownloadWork.uniqueName(trackId)).first()
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active == null) {
            schedule(trackId, wifiOnly, ExistingWorkPolicy.KEEP)
        } else {
            workManager.updateWork(DownloadWork.request(trackId, wifiOnly, existingId = active.id))
        }
    }

    private fun schedule(trackId: String, wifiOnly: Boolean, policy: ExistingWorkPolicy) {
        workManager.enqueueUniqueWork(DownloadWork.uniqueName(trackId), policy, DownloadWork.request(trackId, wifiOnly))
    }

    /** Un titre déjà en file / en cours, ou terminé avec son fichier, n'est pas relancé. */
    private fun DownloadEntity.isActiveOrDone(): Boolean = when (state) {
        DownloadState.QUEUED.name, DownloadState.RUNNING.name -> true
        DownloadState.COMPLETED.name -> filePath?.let { File(it).isFile } == true
        else -> false
    }

    // endregion

    private companion object {
        const val TAG = "DownloadRepository"
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
        val PENDING_STATES = setOf(DownloadState.QUEUED.name, DownloadState.RUNNING.name)
    }
}
