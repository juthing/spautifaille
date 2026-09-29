package com.spautifaille.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Téléchargement joint aux métadonnées du titre. */
data class DownloadWithTrack(
    @androidx.room.Embedded val download: DownloadEntity,
    @androidx.room.Relation(parentColumn = "track_id", entityColumn = "id") val track: TrackEntity,
)

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadWithTrack>>

    @Transaction
    @Query("SELECT * FROM downloads WHERE track_id = :trackId")
    fun observe(trackId: String): Flow<DownloadWithTrack?>

    @Query("SELECT * FROM downloads WHERE track_id = :trackId")
    suspend fun get(trackId: String): DownloadEntity?

    /** Lecture synchrone depuis le thread de chargement du lecteur. */
    @Query("SELECT file_path FROM downloads WHERE track_id = :trackId AND state = 'COMPLETED'")
    fun completedFilePathBlocking(trackId: String): String?

    @Query("SELECT * FROM downloads WHERE state = :state")
    suspend fun byState(state: String): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE track_id = :trackId")
    suspend fun delete(trackId: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    /** Insère la ligne si elle n'existe pas encore. Renvoie -1 si elle existait déjà. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(download: DownloadEntity): Long

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    /** Titres dont le téléchargement n'est pas terminé et qui doivent avoir un travail WorkManager actif. */
    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun pending(): List<DownloadEntity>

    /** Progression : sans effet si la ligne a été supprimée entre-temps (annulation). */
    @Query(
        """
        UPDATE downloads SET state = 'RUNNING', downloaded_bytes = :downloadedBytes, total_bytes = :totalBytes,
            error = NULL, updated_at = :now
        WHERE track_id = :trackId
        """,
    )
    suspend fun updateProgress(trackId: String, downloadedBytes: Long, totalBytes: Long?, now: Long): Int

    @Query(
        """
        UPDATE downloads SET state = 'COMPLETED', downloaded_bytes = :sizeBytes, total_bytes = :sizeBytes,
            file_path = :filePath, mime_type = :mimeType, error = NULL, updated_at = :now
        WHERE track_id = :trackId
        """,
    )
    suspend fun markCompleted(trackId: String, filePath: String, mimeType: String?, sizeBytes: Long, now: Long): Int

    @Query(
        """
        UPDATE downloads SET state = 'FAILED', error = :error, file_path = NULL, updated_at = :now
        WHERE track_id = :trackId
        """,
    )
    suspend fun markFailed(trackId: String, error: String, now: Long): Int

    /** Transition conditionnelle (ex. RUNNING -> QUEUED quand le système interrompt un worker). */
    @Query("UPDATE downloads SET state = :to, updated_at = :now WHERE track_id = :trackId AND state = :from")
    suspend fun changeState(trackId: String, from: String, to: String, now: Long): Int

    /** Remet un téléchargement échoué en file : l'octet déjà reçu est conservé (reprise du `.part`). */
    @Query("UPDATE downloads SET state = 'QUEUED', error = NULL, updated_at = :now WHERE track_id = :trackId AND state != 'COMPLETED'")
    suspend fun requeue(trackId: String, now: Long): Int
}

@Dao
interface ImportDao {
    @Insert
    suspend fun insertJob(job: ImportJobEntity): Long

    @Update
    suspend fun updateJob(job: ImportJobEntity)

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    suspend fun job(id: Long): ImportJobEntity?

    @Query("SELECT * FROM import_jobs WHERE id = :id")
    fun observeJob(id: Long): Flow<ImportJobEntity?>

    @Query("SELECT * FROM import_jobs ORDER BY created_at DESC")
    fun observeJobs(): Flow<List<ImportJobEntity>>

    @Query("DELETE FROM import_jobs WHERE id = :id")
    suspend fun deleteJob(id: Long)

    @Insert
    suspend fun insertItems(items: List<ImportItemEntity>): List<Long>

    @Update
    suspend fun updateItem(item: ImportItemEntity)

    @Query("SELECT * FROM import_items WHERE id = :id")
    suspend fun item(id: Long): ImportItemEntity?

    @Query("SELECT * FROM import_items WHERE job_id = :jobId ORDER BY position")
    suspend fun items(jobId: Long): List<ImportItemEntity>

    @Query("SELECT * FROM import_items WHERE job_id = :jobId ORDER BY position")
    fun observeItems(jobId: Long): Flow<List<ImportItemEntity>>

    /** Titres restant à traiter (reprise après interruption : seuls les `PENDING` sont retraités). */
    @Query("SELECT * FROM import_items WHERE job_id = :jobId AND status = 'PENDING' ORDER BY position")
    suspend fun pendingItems(jobId: Long): List<ImportItemEntity>

    @Query("SELECT COUNT(*) FROM import_items WHERE job_id = :jobId AND status = :status")
    suspend fun countByStatus(jobId: Long, status: String): Int

    /** Ajoute directement une entrée de playlist (l'appelant garantit l'existence du titre et des positions). */
    @Insert
    suspend fun insertEntry(entry: PlaylistEntryEntity): Long

    /**
     * Position (dans la playlist cible) de l'entrée du dernier titre importé situé avant [position] et encore
     * présent dans la playlist, ou `null`.
     */
    @Query(
        """
        SELECT e.position FROM import_items i JOIN playlist_entries e ON e.entry_id = i.entry_id
        WHERE i.job_id = :jobId AND i.position < :position AND e.playlist_id = :playlistId
        ORDER BY i.position DESC LIMIT 1
        """,
    )
    suspend fun positionOfPrecedingEntry(jobId: Long, playlistId: Long, position: Int): Int?

    /** Idem pour le premier titre importé situé après [position]. */
    @Query(
        """
        SELECT e.position FROM import_items i JOIN playlist_entries e ON e.entry_id = i.entry_id
        WHERE i.job_id = :jobId AND i.position > :position AND e.playlist_id = :playlistId
        ORDER BY i.position ASC LIMIT 1
        """,
    )
    suspend fun positionOfFollowingEntry(jobId: Long, playlistId: Long, position: Int): Int?
}

/** Entrée de découverte jointe aux métadonnées du titre. */
data class DiscoveryWithTrack(
    @androidx.room.Embedded val item: DiscoveryTrackEntity,
    @androidx.room.Relation(parentColumn = "track_id", entityColumn = "id") val track: TrackEntity,
)

@Dao
abstract class DiscoveryDao {
    @Query("SELECT * FROM discovery_tracks ORDER BY position")
    abstract fun observe(): Flow<List<DiscoveryTrackEntity>>

    /** Découverte dans l'ordre d'affichage, avec les métadonnées des titres. */
    @Transaction
    @Query("SELECT * FROM discovery_tracks ORDER BY position")
    abstract fun observeWithTracks(): Flow<List<DiscoveryWithTrack>>

    @Query("DELETE FROM discovery_tracks")
    protected abstract suspend fun clear()

    @Insert
    protected abstract suspend fun insertAll(items: List<DiscoveryTrackEntity>)

    @Transaction
    open suspend fun replace(items: List<DiscoveryTrackEntity>) {
        clear()
        insertAll(items)
    }
}
