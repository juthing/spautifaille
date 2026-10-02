package com.spautifaille.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Entrée d'une playlist locale, sans les métadonnées du titre. */
data class PlaylistEntryRow(
    @ColumnInfo(name = "entry_id") val entryId: Long,
    val position: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
)

/** Accès Room de la synchronisation du compte YouTube : liens de playlists, instantanés, file d'actions. */
@Dao
abstract class YouTubeSyncDao {

    // region Liens de playlists

    @Query("SELECT * FROM yt_playlist_links ORDER BY linked_at ASC, playlist_id ASC")
    abstract suspend fun allLinks(): List<YtPlaylistLinkEntity>

    @Query("SELECT * FROM yt_playlist_links WHERE playlist_id = :playlistId")
    abstract suspend fun link(playlistId: Long): YtPlaylistLinkEntity?

    @Query("SELECT * FROM yt_playlist_links WHERE youtube_playlist_id = :youtubeId")
    abstract suspend fun linkByYoutubeId(youtubeId: String): YtPlaylistLinkEntity?

    @Query("SELECT playlist_id FROM yt_playlist_links")
    abstract fun observeLinkedPlaylistIds(): Flow<List<Long>>

    @Upsert
    abstract suspend fun upsertLink(link: YtPlaylistLinkEntity)

    @Query("DELETE FROM yt_playlist_links WHERE playlist_id = :playlistId")
    abstract suspend fun deleteLink(playlistId: Long)

    @Query("UPDATE yt_playlist_links SET last_synced_at = :at WHERE playlist_id = :playlistId")
    abstract suspend fun markSynced(playlistId: Long, at: Long)

    // endregion

    // region Entrées

    @Query("SELECT entry_id, position, track_id FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position ASC, entry_id ASC")
    abstract suspend fun entryRows(playlistId: Long): List<PlaylistEntryRow>

    @Query(
        "SELECT l.* FROM yt_entry_links l JOIN playlist_entries e ON e.entry_id = l.entry_id WHERE e.playlist_id = :playlistId",
    )
    abstract suspend fun entryLinks(playlistId: Long): List<YtEntryLinkEntity>

    @Query("SELECT set_video_id FROM yt_entry_links WHERE entry_id = :entryId")
    abstract suspend fun setVideoIdOf(entryId: Long): String?

    @Upsert
    abstract suspend fun upsertEntryLinks(links: List<YtEntryLinkEntity>)

    @Query("DELETE FROM yt_entry_links WHERE entry_id IN (SELECT entry_id FROM playlist_entries WHERE playlist_id = :playlistId)")
    abstract suspend fun deleteEntryLinksOf(playlistId: Long)

    @Query("DELETE FROM yt_entry_links")
    abstract suspend fun deleteAllEntryLinks()

    @Query("SELECT DISTINCT track_id FROM playlist_entries WHERE playlist_id = :playlistId")
    abstract suspend fun trackIds(playlistId: Long): List<String>

    // endregion

    // region Instantanés

    @Query("SELECT item_key FROM yt_sync_snapshots WHERE scope = :scope AND position >= 0 ORDER BY position ASC")
    abstract suspend fun snapshotKeys(scope: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM yt_sync_snapshots WHERE scope = :scope AND position = -1)")
    abstract suspend fun hasSnapshot(scope: String): Boolean

    @Query("DELETE FROM yt_sync_snapshots WHERE scope = :scope")
    abstract suspend fun deleteSnapshot(scope: String)

    @Insert
    abstract suspend fun insertSnapshotRows(rows: List<YtSnapshotEntity>)

    @Query("SELECT DISTINCT scope FROM yt_sync_snapshots WHERE scope LIKE 'PL:%'")
    abstract suspend fun playlistSnapshotScopes(): List<String>

    @Query("DELETE FROM yt_sync_snapshots")
    abstract suspend fun deleteAllSnapshots()

    /** Instantané [scope], ou `null` s'il n'a jamais été enregistré. */
    @Transaction
    open suspend fun snapshot(scope: String): List<String>? =
        if (hasSnapshot(scope)) snapshotKeys(scope) else null

    @Transaction
    open suspend fun replaceSnapshot(scope: String, keys: List<String>) {
        deleteSnapshot(scope)
        insertSnapshotRows(
            buildList {
                add(YtSnapshotEntity(scope, MARKER_POSITION, ""))
                keys.forEachIndexed { index, key -> add(YtSnapshotEntity(scope, index, key)) }
            },
        )
    }

    // endregion

    // region File d'actions

    @Query("SELECT * FROM yt_pending_actions ORDER BY id ASC")
    abstract suspend fun pendingActions(): List<YtPendingActionEntity>

    @Query("SELECT COUNT(*) FROM yt_pending_actions")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM yt_pending_actions")
    abstract suspend fun pendingCount(): Int

    @Query("DELETE FROM yt_pending_actions WHERE kind = :kind AND target = :target")
    abstract suspend fun deleteAction(kind: String, target: String)

    @Query("DELETE FROM yt_pending_actions WHERE id = :id")
    abstract suspend fun deleteActionById(id: Long)

    @Insert
    abstract suspend fun insertAction(action: YtPendingActionEntity): Long

    @Query("UPDATE yt_pending_actions SET attempts = attempts + 1, last_error = :error WHERE id = :id")
    abstract suspend fun recordFailure(id: Long, error: String?)

    @Query("DELETE FROM yt_pending_actions")
    abstract suspend fun deleteAllActions()

    /** Remplace l'éventuelle action déjà en attente pour la même cible (dernière intention gagnante). */
    @Transaction
    open suspend fun enqueue(kind: String, target: String, enabled: Boolean, now: Long) {
        deleteAction(kind, target)
        insertAction(YtPendingActionEntity(kind = kind, target = target, enabled = enabled, createdAt = now))
    }

    // endregion

    /** Déconnexion : oublie tout ce qui lie les données locales à un compte (les données locales restent). */
    @Transaction
    open suspend fun clearAccountData() {
        deleteAllActions()
        deleteAllSnapshots()
        deleteAllEntryLinks()
        deleteAllLinks()
    }

    @Query("DELETE FROM yt_playlist_links")
    abstract suspend fun deleteAllLinks()

    companion object {
        const val MARKER_POSITION = -1
    }
}
