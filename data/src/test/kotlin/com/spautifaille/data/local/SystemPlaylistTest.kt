package com.spautifaille.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spautifaille.domain.model.Playlist
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.room.Room

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class SystemPlaylistTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openFileDb(name: String) =
        Room.databaseBuilder(context, SpautifailleDatabase::class.java, name)
            .allowMainThreadQueries()
            .configureSpautifaille()
            .build()

    @Test
    fun likedPlaylistIsRecreatedOnNextOpenIfMissing() {
        val name = "system-playlist-test.db"
        context.deleteDatabase(name)

        val first = openFileDb(name)
        first.openHelper.writableDatabase.execSQL("DELETE FROM playlists")
        first.close()

        val second = openFileDb(name)
        try {
            second.openHelper.writableDatabase.query("SELECT id, name, is_system FROM playlists").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(Playlist.LIKED_ID, c.getLong(0))
                assertEquals(LIKED_PLAYLIST_NAME, c.getString(1))
                assertEquals(1, c.getInt(2))
            }
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun onOpenDoesNotDuplicateOrOverwriteExistingLikedPlaylist() {
        val name = "system-playlist-idempotent.db"
        context.deleteDatabase(name)

        openFileDb(name).apply {
            openHelper.writableDatabase.execSQL("UPDATE playlists SET updated_at = 42 WHERE id = ${Playlist.LIKED_ID}")
            close()
        }
        val reopened = openFileDb(name)
        try {
            reopened.openHelper.writableDatabase.query("SELECT COUNT(*), MAX(updated_at) FROM playlists").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
                assertEquals(42L, c.getLong(1))
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
