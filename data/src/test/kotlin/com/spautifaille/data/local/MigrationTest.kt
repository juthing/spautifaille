package com.spautifaille.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spautifaille.domain.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Les schémas exportés (`data/schemas`) sont exposés aux tests unitaires comme assets (voir `data/build.gradle.kts`).
 * Pour chaque nouvelle version N+1 : ajouter la `Migration(N, N + 1)` à [ALL_MIGRATIONS], puis un test
 * `migrateNToN+1` qui crée la base en version N, insère des données, appelle `runMigrationsAndValidate`
 * et vérifie les données conservées.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SpautifailleDatabase::class.java,
    )

    @Test
    fun version1SchemaCreatesAndOpensWithAllMigrations() = runTest {
        // Base de la version 1 telle que décrite par 1.json, avec quelques données.
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, artist_url, album, duration_ms, thumbnail_url, updated_at) " +
                    "VALUES ('abc', 'Titre', 'Artiste', NULL, NULL, 1000, NULL, 1)",
            )
        }

        // Ouverture par Room avec la configuration de production : valide le schéma et enchaîne les migrations.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, SpautifailleDatabase::class.java, TEST_DB)
            .configureSpautifaille()
            .build()
        try {
            assertEquals("Titre", db.trackDao().get("abc")?.title)
            // « Titres likés » est garantie à l'ouverture même sur une base v1 préexistante sans elle.
            db.openHelper.writableDatabase.query(
                "SELECT name, is_system FROM playlists WHERE id = ${Playlist.LIKED_ID}",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(LIKED_PLAYLIST_NAME, c.getString(0))
                assertEquals(1, c.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun allMigrationsRunAndValidateUpToLatestVersion() {
        val latest = 3
        helper.createDatabase(TEST_DB_MIGRATED, 1).close()
        // Sans migration déclarée, la validation reste sur la version 1 ; chaque future migration
        // sera enchaînée ici automatiquement.
        helper.runMigrationsAndValidate(TEST_DB_MIGRATED, latest, true, *ALL_MIGRATIONS).close()
    }

    @Test
    fun migrate1To2KeepsDataAndCreatesNewTables() {
        helper.createDatabase(TEST_DB_1_2, 1).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, artist_url, album, duration_ms, thumbnail_url, updated_at) " +
                    "VALUES ('abc', 'Titre', 'Artiste', NULL, NULL, 1000, NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO playlists (id, name, is_system, created_at, updated_at, thumbnail_url) " +
                    "VALUES (5, 'Ma playlist', 0, 1, 1, NULL)",
            )
            db.execSQL(
                "INSERT INTO playlist_entries (playlist_id, track_id, position, added_at) VALUES (5, 'abc', 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB_1_2, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = 5").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.execSQL(
                "INSERT INTO downloads (track_id, state, downloaded_bytes, total_bytes, file_path, mime_type, error, " +
                    "created_at, updated_at) VALUES ('abc', 'COMPLETED', 10, 10, '/f', 'audio/webm', NULL, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO import_jobs (playlist_name, format, state, total, processed, matched, needs_review, " +
                    "not_found, target_playlist_id, error, created_at) VALUES ('x', 'EXPORTIFY_CSV', 'RUNNING', " +
                    "1, 0, 0, 0, 0, 5, NULL, 1)",
            )
            // Suppression de la playlist cible : le job reste, target_playlist_id passe à NULL.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM playlists WHERE id = 5")
            db.query("SELECT target_playlist_id FROM import_jobs").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
        }
    }

    @Test
    fun migrate2To3KeepsDataAndCreatesYouTubeTables() {
        helper.createDatabase(TEST_DB_2_3, 2).use { db ->
            db.execSQL(
                "INSERT INTO tracks (id, title, artist, artist_url, album, duration_ms, thumbnail_url, updated_at) " +
                    "VALUES ('abc', 'Titre', 'Artiste', NULL, NULL, 1000, NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO playlists (id, name, is_system, created_at, updated_at, thumbnail_url) " +
                    "VALUES (5, 'Ma playlist', 0, 1, 1, NULL)",
            )
            db.execSQL(
                "INSERT INTO playlist_entries (entry_id, playlist_id, track_id, position, added_at) VALUES (7, 5, 'abc', 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB_2_3, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = 5").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.execSQL(
                "INSERT INTO yt_playlist_links (playlist_id, youtube_playlist_id, read_only, linked_at, last_synced_at) " +
                    "VALUES (5, 'PLxyz', 0, 1, NULL)",
            )
            db.execSQL("INSERT INTO yt_entry_links (entry_id, set_video_id) VALUES (7, 'SVID')")
            db.execSQL("INSERT INTO yt_sync_snapshots (scope, position, item_key) VALUES ('PL:5', -1, '')")
            db.execSQL(
                "INSERT INTO yt_pending_actions (kind, target, enabled, created_at, attempts, last_error) " +
                    "VALUES ('LIKE', 'abc', 1, 1, 0, NULL)",
            )
            // Une seule action en attente par (kind, target).
            val duplicate = runCatching {
                db.execSQL(
                    "INSERT INTO yt_pending_actions (kind, target, enabled, created_at, attempts, last_error) " +
                        "VALUES ('LIKE', 'abc', 0, 2, 0, NULL)",
                )
            }
            assertTrue(duplicate.isFailure)
            // Supprimer la playlist supprime en cascade son lien, puis le lien de ses entrées.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM playlists WHERE id = 5")
            db.query("SELECT COUNT(*) FROM yt_playlist_links").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM yt_entry_links").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DB_2_3 = "migration-2-3.db"
        const val TEST_DB_1_2 = "migration-1-2.db"
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_MIGRATED = "migration-test-chain.db"
    }
}
