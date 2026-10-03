package com.spautifaille.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spautifaille.domain.model.Playlist

/**
 * Base de données locale. Toute évolution du schéma = nouvelle version + `Migration` explicite ajoutée à
 * [ALL_MIGRATIONS] + cas dans `MigrationTest`. Aucun repli destructif.
 */
@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        HistoryEntity::class,
        SubscriptionEntity::class,
        QueueItemEntity::class,
        QueueStateEntity::class,
        DownloadEntity::class,
        ImportJobEntity::class,
        ImportItemEntity::class,
        DiscoveryTrackEntity::class,
        YtPlaylistLinkEntity::class,
        YtEntryLinkEntity::class,
        YtSnapshotEntity::class,
        YtPendingActionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SpautifailleDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun queueDao(): QueueDao
    abstract fun downloadDao(): DownloadDao
    abstract fun importDao(): ImportDao
    abstract fun discoveryDao(): DiscoveryDao
    abstract fun youTubeSyncDao(): YouTubeSyncDao

    companion object {
        const val NAME = "spautifaille.db"
    }
}

/** Nom de la playlist système des titres likés. */
const val LIKED_PLAYLIST_NAME = "Titres likés"

/**
 * Garantit l'existence de la playlist système « Titres likés » : à la création de la base ([onCreate]) et à
 * chaque ouverture ([onOpen], `INSERT OR IGNORE`) pour être robuste à une suppression accidentelle.
 */
class SystemPlaylistsCallback(
    private val now: () -> Long = System::currentTimeMillis,
) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) = ensureLikedPlaylist(db)

    override fun onOpen(db: SupportSQLiteDatabase) = ensureLikedPlaylist(db)

    private fun ensureLikedPlaylist(db: SupportSQLiteDatabase) {
        if (db.isReadOnly) return
        val timestamp = now()
        db.execSQL(
            "INSERT OR IGNORE INTO playlists (id, name, is_system, created_at, updated_at, thumbnail_url) " +
                "VALUES (?, ?, 1, ?, ?, NULL)",
            arrayOf<Any>(Playlist.LIKED_ID, LIKED_PLAYLIST_NAME, timestamp, timestamp),
        )
    }
}

/** Configuration commune (production et tests) : callback système + migrations, jamais de repli destructif. */
fun RoomDatabase.Builder<SpautifailleDatabase>.configureSpautifaille(): RoomDatabase.Builder<SpautifailleDatabase> =
    addCallback(SystemPlaylistsCallback())
        .addMigrations(*ALL_MIGRATIONS)
