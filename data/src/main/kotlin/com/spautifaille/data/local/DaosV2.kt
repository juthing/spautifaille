package com.spautifaille.data.local

import androidx.room.Dao
import androidx.room.Insert
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
}

@Dao
abstract class DiscoveryDao {
    @Query("SELECT * FROM discovery_tracks ORDER BY position")
    abstract fun observe(): Flow<List<DiscoveryTrackEntity>>

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
