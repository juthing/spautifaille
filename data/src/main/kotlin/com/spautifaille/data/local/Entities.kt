package com.spautifaille.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cache de métadonnées de titres. [id] = identifiant vidéo YouTube. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "artist_url") val artistUrl: String?,
    val album: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Pochette personnalisée (non utilisée pour l'instant) ; sinon la pochette du premier titre. */
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String? = null,
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
        ),
    ],
    indices = [
        Index(value = ["playlist_id", "position"]),
        Index(value = ["track_id"]),
    ],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "entry_id") val entryId: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_id") val trackId: String,
    /** Position 0-based, contiguë au sein d'une playlist. */
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
        ),
    ],
    indices = [
        Index(value = ["played_at"]),
        Index(value = ["track_id"]),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val url: String,
    val name: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    @ColumnInfo(name = "subscribed_at") val subscribedAt: Long,
)

/** Élément de la file persistée. [position] est la clé : la file est toujours réécrite en bloc. */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
    val uid: String,
)

/** Ligne unique (id = 0) décrivant l'état de lecture de la file. */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "current_index") val currentIndex: Int,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "shuffle_enabled") val shuffleEnabled: Boolean,
    /** Nom de [com.spautifaille.domain.player.RepeatMode]. */
    @ColumnInfo(name = "repeat_mode") val repeatMode: String,
    /** Indices séparés par des virgules ; chaîne vide si non mélangé. */
    @ColumnInfo(name = "shuffle_order") val shuffleOrder: String,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
