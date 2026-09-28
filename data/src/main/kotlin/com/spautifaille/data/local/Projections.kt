package com.spautifaille.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** Playlist + agrégats calculés par la requête (nombre de titres, pochette). */
data class PlaylistRow(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String?,
)

data class EntryWithTrack(
    @ColumnInfo(name = "entry_id") val entryId: Long,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @Embedded val track: TrackEntity,
)

data class HistoryWithTrack(
    @ColumnInfo(name = "history_id") val historyId: Long,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    @Embedded val track: TrackEntity,
)

data class PlayCountRow(
    @Embedded val track: TrackEntity,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long,
)

data class QueueItemWithTrack(
    val position: Int,
    val uid: String,
    @Embedded val track: TrackEntity,
)
