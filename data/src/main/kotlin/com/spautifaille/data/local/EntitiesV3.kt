package com.spautifaille.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Tables ajoutées en version 3 (MIGRATION_2_3) : liaison des playlists locales avec le compte YouTube.

/**
 * Lien entre une playlist locale et une playlist YouTube. [readOnly] : playlist d'un autre auteur, importée en
 * miroir (les modifications locales ne sont pas poussées et le distant l'emporte).
 */
@Entity(
    tableName = "yt_playlist_links",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["youtube_playlist_id"], unique = true)],
)
data class YtPlaylistLinkEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "youtube_playlist_id") val youtubePlaylistId: String,
    @ColumnInfo(name = "read_only") val readOnly: Boolean,
    @ColumnInfo(name = "linked_at") val linkedAt: Long,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long?,
)

/** `setVideoId` YouTube d'une entrée de playlist liée (identifie l'entrée pour la suppression et le déplacement). */
@Entity(
    tableName = "yt_entry_links",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntryEntity::class,
            parentColumns = ["entry_id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class YtEntryLinkEntity(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "set_video_id") val setVideoId: String,
)

/**
 * Instantané du dernier état synchronisé d'une liste, base de la réconciliation à trois voies. [scope] :
 * `LIKES`, `LIKES_SEEN`, `LIKES_MODE`, `SUBS`, `SUBS_SEEN` ou `PL:<id de playlist locale>`. Une ligne de position
 * `-1` marque l'existence de l'instantané (distingue « jamais synchronisé » de « synchronisé vide »).
 */
@Entity(
    tableName = "yt_sync_snapshots",
    primaryKeys = ["scope", "position"],
)
data class YtSnapshotEntity(
    val scope: String,
    val position: Int,
    @ColumnInfo(name = "item_key") val itemKey: String,
)

/**
 * Écriture distante en attente (file persistée rejouée par `YouTubeSyncWorker`). Une seule ligne par
 * ([kind], [target]) : une nouvelle action remplace la précédente (ex. like puis unlike).
 * [kind] = `LIKE`, `SUBSCRIPTION`, `PLAYLIST_SYNC` ou `PLAYLIST_PUBLISH` ; [target] = id de vidéo, id de chaîne
 * ou id de playlist locale ; [enabled] = état voulu (liké / abonné) pour les deux premiers types.
 */
@Entity(
    tableName = "yt_pending_actions",
    indices = [Index(value = ["kind", "target"], unique = true)],
)
data class YtPendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val target: String,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
