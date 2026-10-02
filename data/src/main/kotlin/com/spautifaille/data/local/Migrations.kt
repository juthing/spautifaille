package com.spautifaille.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrations Room, dans l'ordre. Ajouter ici chaque `Migration(n, n + 1)` en même temps que
 * l'incrément de `@Database(version)` et un test dans `MigrationTest`.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

/** v3 : compte YouTube (liens de playlists, `setVideoId` des entrées, instantanés de synchro, file d'actions). Tables nouvelles. */
object MIGRATION_2_3 : Migration(2, 3) {
    // SQL repris à l'identique de schemas/.../3.json (createSql générés par Room).
    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `yt_playlist_links` (`playlist_id` INTEGER NOT NULL, `youtube_playlist_id` TEXT NOT NULL, `read_only` INTEGER NOT NULL, `linked_at` INTEGER NOT NULL, `last_synced_at` INTEGER, PRIMARY KEY(`playlist_id`), FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_yt_playlist_links_youtube_playlist_id` ON `yt_playlist_links` (`youtube_playlist_id`)",
        "CREATE TABLE IF NOT EXISTS `yt_entry_links` (`entry_id` INTEGER NOT NULL, `set_video_id` TEXT NOT NULL, PRIMARY KEY(`entry_id`), FOREIGN KEY(`entry_id`) REFERENCES `playlist_entries`(`entry_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `yt_sync_snapshots` (`scope` TEXT NOT NULL, `position` INTEGER NOT NULL, `item_key` TEXT NOT NULL, PRIMARY KEY(`scope`, `position`))",
        "CREATE TABLE IF NOT EXISTS `yt_pending_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `target` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `last_error` TEXT)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_yt_pending_actions_kind_target` ON `yt_pending_actions` (`kind`, `target`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }
}

/** v2 : téléchargements, imports de playlists, cache Découverte (tables nouvelles, aucune donnée modifiée). */
object MIGRATION_1_2 : Migration(1, 2) {
    // SQL repris à l'identique de schemas/.../2.json (createSql générés par Room).
    private val statements = listOf(
            "CREATE TABLE IF NOT EXISTS `downloads` (`track_id` TEXT NOT NULL, `state` TEXT NOT NULL, `downloaded_bytes` INTEGER NOT NULL, `total_bytes` INTEGER, `file_path` TEXT, `mime_type` TEXT, `error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`track_id`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_downloads_state` ON `downloads` (`state`)",
            "CREATE TABLE IF NOT EXISTS `import_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlist_name` TEXT NOT NULL, `format` TEXT NOT NULL, `state` TEXT NOT NULL, `total` INTEGER NOT NULL, `processed` INTEGER NOT NULL, `matched` INTEGER NOT NULL, `needs_review` INTEGER NOT NULL, `not_found` INTEGER NOT NULL, `target_playlist_id` INTEGER, `error` TEXT, `created_at` INTEGER NOT NULL, FOREIGN KEY(`target_playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_import_jobs_target_playlist_id` ON `import_jobs` (`target_playlist_id`)",
            "CREATE TABLE IF NOT EXISTS `import_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `job_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `source_title` TEXT NOT NULL, `source_artists` TEXT NOT NULL, `source_album` TEXT, `source_duration_ms` INTEGER, `source_isrc` TEXT, `source_youtube_id` TEXT, `status` TEXT NOT NULL, `best_track_id` TEXT, `best_score` REAL, `candidates` TEXT, `entry_id` INTEGER, FOREIGN KEY(`job_id`) REFERENCES `import_jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_import_items_job_id_position` ON `import_items` (`job_id`, `position`)",
            "CREATE INDEX IF NOT EXISTS `index_import_items_job_id_status` ON `import_items` (`job_id`, `status`)",
            "CREATE TABLE IF NOT EXISTS `discovery_tracks` (`position` INTEGER NOT NULL, `track_id` TEXT NOT NULL, `seed_track_id` TEXT, `source` TEXT NOT NULL, `generated_at` INTEGER NOT NULL, PRIMARY KEY(`position`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_discovery_tracks_track_id` ON `discovery_tracks` (`track_id`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }
}
