package com.spautifaille.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Tables ajoutées en version 2 (MIGRATION_1_2) : téléchargements, imports, découverte.

/** Téléchargement d'un titre. [state] = nom de `DownloadState`. [filePath] n'est renseigné qu'une fois complet. */
@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"]),
    ],
    indices = [Index(value = ["state"])],
)
data class DownloadEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: String,
    val state: String,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long?,
    @ColumnInfo(name = "file_path") val filePath: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Job d'import d'une playlist externe. [format] = nom d'`ImportFormat`, [state] = nom d'`ImportJobState`. */
@Entity(
    tableName = "import_jobs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_playlist_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["target_playlist_id"])],
)
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_name") val playlistName: String,
    val format: String,
    val state: String,
    val total: Int,
    val processed: Int,
    val matched: Int,
    @ColumnInfo(name = "needs_review") val needsReview: Int,
    @ColumnInfo(name = "not_found") val notFound: Int,
    @ColumnInfo(name = "target_playlist_id") val targetPlaylistId: Long?,
    val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Titre d'un job d'import. Les champs `source_*` reprennent l'`ImportedTrack` d'origine
 * ([sourceArtists] : artistes séparés par le caractère U+001F).
 * [status] = nom de `MatchStatus`. [candidates] = JSON `[{"trackId":"…","score":0.93}, …]` trié par score
 * décroissant (les métadonnées des candidats sont dans `tracks`). [entryId] = entrée créée dans la playlist cible.
 */
@Entity(
    tableName = "import_items",
    foreignKeys = [
        ForeignKey(
            entity = ImportJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["job_id", "position"]), Index(value = ["job_id", "status"])],
)
data class ImportItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "job_id") val jobId: Long,
    val position: Int,
    @ColumnInfo(name = "source_title") val sourceTitle: String,
    @ColumnInfo(name = "source_artists") val sourceArtists: String,
    @ColumnInfo(name = "source_album") val sourceAlbum: String?,
    @ColumnInfo(name = "source_duration_ms") val sourceDurationMs: Long?,
    @ColumnInfo(name = "source_isrc") val sourceIsrc: String?,
    @ColumnInfo(name = "source_youtube_id") val sourceYoutubeId: String?,
    val status: String,
    @ColumnInfo(name = "best_track_id") val bestTrackId: String?,
    @ColumnInfo(name = "best_score") val bestScore: Double?,
    val candidates: String?,
    @ColumnInfo(name = "entry_id") val entryId: Long?,
)

/** Résultat mis en cache du mode Découverte, dans l'ordre d'affichage. */
@Entity(
    tableName = "discovery_tracks",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"]),
    ],
    indices = [Index(value = ["track_id"])],
)
data class DiscoveryTrackEntity(
    @PrimaryKey val position: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "seed_track_id") val seedTrackId: String?,
    val source: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
)
