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
        val latest = 1
        helper.createDatabase(TEST_DB_MIGRATED, 1).close()
        // Sans migration déclarée, la validation reste sur la version 1 ; chaque future migration
        // sera enchaînée ici automatiquement.
        helper.runMigrationsAndValidate(TEST_DB_MIGRATED, latest, true, *ALL_MIGRATIONS).close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_MIGRATED = "migration-test-chain.db"
    }
}
