package com.spautifaille.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<TrackEntity>
}

private const val PLAYLIST_ROW_SELECT = """
    SELECT p.id AS id, p.name AS name, p.is_system AS is_system,
           p.created_at AS created_at, p.updated_at AS updated_at,
           (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlist_id = p.id) AS track_count,
           COALESCE(
               p.thumbnail_url,
               (SELECT t.thumbnail_url FROM playlist_entries e
                    JOIN tracks t ON t.id = e.track_id
                    WHERE e.playlist_id = p.id
                    ORDER BY e.position ASC, e.entry_id ASC LIMIT 1)
           ) AS thumbnail_url,
           EXISTS(SELECT 1 FROM yt_playlist_links l WHERE l.playlist_id = p.id) AS is_linked
    FROM playlists p
"""

@Dao
abstract class PlaylistDao {

    // region Playlists

    /** « Titres likés » (système) en premier, puis par dernière modification. */
    @Query("$PLAYLIST_ROW_SELECT ORDER BY p.is_system DESC, p.updated_at DESC, p.id DESC")
    abstract fun observePlaylists(): Flow<List<PlaylistRow>>

    @Query("$PLAYLIST_ROW_SELECT WHERE p.id = :id")
    abstract fun observePlaylist(id: Long): Flow<PlaylistRow?>

    @Query(
        """
        SELECT e.entry_id AS entry_id, e.position AS position, e.added_at AS added_at, t.*
        FROM playlist_entries e JOIN tracks t ON t.id = e.track_id
        WHERE e.playlist_id = :playlistId
        ORDER BY e.position ASC, e.entry_id ASC
        """,
    )
    abstract fun observeEntries(playlistId: Long): Flow<List<EntryWithTrack>>

    @Insert
    abstract suspend fun insert(playlist: PlaylistEntity): Long

    /** Sans effet sur une playlist système. Renvoie le nombre de lignes modifiées. */
    @Query("UPDATE playlists SET name = :name, updated_at = :now WHERE id = :id AND is_system = 0")
    abstract suspend fun rename(id: Long, name: String, now: Long): Int

    /** Sans effet sur une playlist système. Les entrées sont supprimées en cascade. */
    @Query("DELETE FROM playlists WHERE id = :id AND is_system = 0")
    abstract suspend fun delete(id: Long): Int

    @Query("UPDATE playlists SET updated_at = :now WHERE id = :id")
    abstract suspend fun touch(id: Long, now: Long)

    // endregion

    // region Entries

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlist_id = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT position FROM playlist_entries WHERE playlist_id = :playlistId AND entry_id = :entryId")
    abstract suspend fun positionOf(playlistId: Long, entryId: Long): Int?

    @Query("SELECT track_id FROM playlist_entries WHERE playlist_id = :playlistId AND entry_id = :entryId")
    abstract suspend fun trackIdOfEntry(playlistId: Long, entryId: Long): String?

    @Query("SELECT entry_id FROM playlist_entries WHERE playlist_id = :playlistId AND position = :position")
    abstract suspend fun entryIdAt(playlistId: Long, position: Int): Long?

    @Query("SELECT entry_id FROM playlist_entries WHERE playlist_id = :playlistId AND track_id = :trackId")
    abstract suspend fun entryIdsOfTrack(playlistId: Long, trackId: String): List<Long>

    @Query("DELETE FROM playlist_entries WHERE entry_id = :entryId")
    abstract suspend fun deleteEntryRow(entryId: Long)

    @Query(
        "UPDATE playlist_entries SET position = position - 1 " +
            "WHERE playlist_id = :playlistId AND position > :removedPosition",
    )
    abstract suspend fun compactAfter(playlistId: Long, removedPosition: Int)

    @Query("UPDATE playlist_entries SET position = :position WHERE entry_id = :entryId")
    abstract suspend fun setPosition(entryId: Long, position: Int)

    /** Décale de -1 les positions de l'intervalle [from, to]. */
    @Query(
        "UPDATE playlist_entries SET position = position - 1 " +
            "WHERE playlist_id = :playlistId AND position BETWEEN :from AND :to",
    )
    abstract suspend fun shiftUp(playlistId: Long, from: Int, to: Int)

    /** Décale de +1 les positions de l'intervalle [from, to]. */
    @Query(
        "UPDATE playlist_entries SET position = position + 1 " +
            "WHERE playlist_id = :playlistId AND position BETWEEN :from AND :to",
    )
    abstract suspend fun shiftDown(playlistId: Long, from: Int, to: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_entries WHERE playlist_id = :playlistId AND track_id = :trackId)")
    abstract suspend fun contains(playlistId: Long, trackId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_entries WHERE playlist_id = :playlistId AND track_id = :trackId)")
    abstract fun observeContains(playlistId: Long, trackId: String): Flow<Boolean>

    @Query("SELECT DISTINCT track_id FROM playlist_entries WHERE playlist_id = :playlistId")
    abstract fun observeTrackIds(playlistId: Long): Flow<List<String>>

    @Query("SELECT DISTINCT track_id FROM playlist_entries")
    abstract suspend fun allTrackIds(): List<String>

    @Query(
        """
        SELECT t.* FROM playlist_entries e JOIN tracks t ON t.id = e.track_id
        WHERE e.playlist_id = :playlistId
        ORDER BY e.added_at DESC, e.entry_id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun latestTracks(playlistId: Long, limit: Int): List<TrackEntity>

    /**
     * Ajoute [trackIds] à la fin de la playlist (positions contiguës). Les titres doivent déjà exister
     * dans `tracks` (clé étrangère).
     */
    @Transaction
    open suspend fun addEntries(playlistId: Long, trackIds: List<String>, now: Long) {
        if (trackIds.isEmpty()) return
        val start = maxPosition(playlistId) + 1
        insertEntries(
            trackIds.mapIndexed { index, trackId ->
                PlaylistEntryEntity(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = start + index,
                    addedAt = now,
                )
            },
        )
        touch(playlistId, now)
    }

    /** Supprime l'entrée puis referme le trou laissé dans les positions. */
    @Transaction
    open suspend fun removeEntry(playlistId: Long, entryId: Long, now: Long) {
        val position = positionOf(playlistId, entryId) ?: return
        deleteEntryRow(entryId)
        compactAfter(playlistId, position)
        touch(playlistId, now)
    }

    /** Supprime toutes les occurrences d'un titre dans la playlist ; renvoie le nombre supprimé. */
    @Transaction
    open suspend fun removeTrack(playlistId: Long, trackId: String, now: Long): Int {
        val ids = entryIdsOfTrack(playlistId, trackId)
        ids.forEach { removeEntry(playlistId, it, now) }
        return ids.size
    }

    /**
     * Déplace l'entrée située à [from] vers [to] (positions 0-based) en décalant les entrées intermédiaires.
     * [to] est borné à la dernière position.
     */
    @Transaction
    open suspend fun moveEntry(playlistId: Long, from: Int, to: Int, now: Long) {
        val last = maxPosition(playlistId)
        if (last < 0 || from !in 0..last) return
        val target = to.coerceIn(0, last)
        if (from == target) return
        val entryId = entryIdAt(playlistId, from) ?: return
        setPosition(entryId, -1)
        if (from < target) shiftUp(playlistId, from + 1, target) else shiftDown(playlistId, target, from - 1)
        setPosition(entryId, target)
        touch(playlistId, now)
    }

    // endregion
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntity): Long

    @Query(
        """
        SELECT h.id AS history_id, h.played_at AS played_at, t.*
        FROM history h JOIN tracks t ON t.id = h.track_id
        ORDER BY h.played_at DESC, h.id DESC
        LIMIT :limit
        """,
    )
    fun observeLatest(limit: Int): Flow<List<HistoryWithTrack>>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT DISTINCT track_id FROM history WHERE played_at >= :sinceMs")
    suspend fun recentIds(sinceMs: Long): List<String>

    @Query(
        """
        SELECT t.*, COUNT(*) AS play_count, MAX(h.played_at) AS last_played_at
        FROM history h JOIN tracks t ON t.id = h.track_id
        WHERE h.played_at >= :sinceMs
        GROUP BY t.id
        ORDER BY play_count DESC, last_played_at DESC, t.id ASC
        LIMIT :limit
        """,
    )
    suspend fun mostPlayed(limit: Int, sinceMs: Long): List<PlayCountRow>
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE ASC, url ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE url = :url)")
    fun observeIsSubscribed(url: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
abstract class QueueDao {
    @Query(
        """
        SELECT q.position AS position, q.uid AS uid, t.*
        FROM queue_items q JOIN tracks t ON t.id = q.track_id
        ORDER BY q.position ASC
        """,
    )
    abstract suspend fun loadItems(): List<QueueItemWithTrack>

    @Query("SELECT * FROM queue_state WHERE id = 0")
    abstract suspend fun loadState(): QueueStateEntity?

    @Query("DELETE FROM queue_items")
    abstract suspend fun deleteItems()

    @Query("DELETE FROM queue_state")
    abstract suspend fun deleteState()

    @Insert
    abstract suspend fun insertItems(items: List<QueueItemEntity>)

    @Upsert
    abstract suspend fun upsertState(state: QueueStateEntity)

    @Query("UPDATE queue_state SET current_index = :currentIndex, position_ms = :positionMs WHERE id = 0")
    abstract suspend fun updatePosition(currentIndex: Int, positionMs: Long): Int

    /** Remplace atomiquement toute la file et son état. */
    @Transaction
    open suspend fun replaceAll(items: List<QueueItemEntity>, state: QueueStateEntity) {
        deleteItems()
        insertItems(items)
        upsertState(state)
    }

    @Transaction
    open suspend fun clear() {
        deleteItems()
        deleteState()
    }
}
