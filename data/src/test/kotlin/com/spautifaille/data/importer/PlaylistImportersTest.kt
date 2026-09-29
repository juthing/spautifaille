package com.spautifaille.data.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class PlaylistImportersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://www.youtube.com/playlist?list=PL1"

    // --- YouTubePlaylistImporter -------------------------------------------------------------------

    @Test
    fun `youtube importer only handles playlist urls`() {
        val stream = FakeStreamRepository()
        val importer = YouTubePlaylistImporter(context, stream)
        assertTrue(importer.canHandle(ImportSource.Url("  $url ")))
        assertFalse(importer.canHandle(ImportSource.Url("https://example.com/")))
        assertFalse(importer.canHandle(ImportSource.File("content://x", null, null)))
    }

    @Test
    fun `youtube importer reads every page and keeps youtube ids`() = runTest {
        val stream = FakeStreamRepository()
        stream.playlistHandler = { token ->
            when ((token as? IntToken)?.n) {
                null -> remotePage("Ma playlist", listOf(ytTrack("a"), ytTrack("b")), IntToken(1))
                1 -> remotePage("", listOf(ytTrack("c")), IntToken(2))
                else -> remotePage("", listOf(ytTrack("d", artist = "")), null)
            }
        }

        val playlists = YouTubePlaylistImporter(context, stream).read(ImportSource.Url(url))

        val playlist = playlists.single()
        assertEquals("Ma playlist", playlist.name)
        assertEquals(ImportFormat.YOUTUBE_URL, playlist.sourceFormat)
        assertEquals(listOf("a", "b", "c", "d"), playlist.tracks.map { it.youtubeId })
        assertEquals(listOf("Titre a"), playlist.tracks.take(1).map { it.title })
        assertEquals(listOf("Artiste a"), playlist.tracks.first().artists)
        assertEquals(200_000L, playlist.tracks.first().durationMs)
        assertTrue(playlist.tracks.last().artists.isEmpty())
        assertEquals(3, stream.playlistRequests.size)
        assertNull(stream.playlistRequests.first())
    }

    @Test
    fun `youtube importer caps the playlist at 5000 tracks`() = runTest {
        val stream = FakeStreamRepository()
        stream.playlistHandler = { token ->
            val page = (token as? IntToken)?.n ?: 0
            remotePage("Énorme", List(100) { ytTrack("t${page * 100 + it}") }, IntToken(page + 1))
        }

        val playlist = YouTubePlaylistImporter(context, stream).read(ImportSource.Url(url)).single()

        assertEquals(YouTubePlaylistImporter.MAX_TRACKS, playlist.tracks.size)
        assertEquals(50, stream.playlistRequests.size)
    }

    @Test
    fun `youtube importer stops on an empty page and rejects an empty playlist`() = runTest {
        val stream = FakeStreamRepository()
        stream.playlistHandler = { remotePage("Vide", emptyList(), IntToken(1)) }

        try {
            YouTubePlaylistImporter(context, stream).read(ImportSource.Url(url))
            fail("exception attendue")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("vide"))
        }
        assertEquals(1, stream.playlistRequests.size)
    }

    @Test
    fun `youtube importer propagates stream errors`() = runTest {
        val stream = FakeStreamRepository()
        stream.playlistHandler = { throw AppException(AppError.Network) }
        try {
            YouTubePlaylistImporter(context, stream).read(ImportSource.Url(url))
            fail("exception attendue")
        } catch (e: AppException) {
            assertEquals(AppError.Network, e.error)
        }
    }

    // --- FilePlaylistImporter ----------------------------------------------------------------------

    private fun fileImporter() = FilePlaylistImporter(context, com.spautifaille.data.importer.parser.PlaylistFileParsers(), UnconfinedTestDispatcher())

    private fun register(uri: String, stream: InputStream) {
        shadowOf(context.contentResolver).registerInputStream(android.net.Uri.parse(uri), stream)
    }

    @Test
    fun `file importer only handles files`() {
        val importer = fileImporter()
        assertTrue(importer.canHandle(ImportSource.File("content://x", "a.csv", null)))
        assertFalse(importer.canHandle(ImportSource.Url(url)))
    }

    @Test
    fun `file importer parses a csv read through the content resolver`() = runTest {
        val csv = "Titre;Artiste;Durée\nAlors on danse;Stromae;3:26\nPapaoutai;Stromae;3:52\n"
        val uri = "content://test.provider/doc/1"
        register(uri, ByteArrayInputStream(csv.toByteArray()))

        val playlists = fileImporter().read(ImportSource.File(uri, "Ma Playlist.csv", "text/csv"))

        val playlist = playlists.single()
        assertEquals(listOf("Alors on danse", "Papaoutai"), playlist.tracks.map { it.title })
        assertEquals(listOf("Stromae"), playlist.tracks.first().artists)
    }

    @Test
    fun `file importer maps parse failures to ImportException`() = runTest {
        val uri = "content://test.provider/doc/2"
        register(uri, ByteArrayInputStream("ceci n'est pas une playlist".toByteArray()))
        try {
            fileImporter().read(ImportSource.File(uri, "junk.txt", "text/plain"))
            fail("exception attendue")
        } catch (e: ImportException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `file importer rejects files over 20 MB`() = runTest {
        val uri = "content://test.provider/doc/3"
        val size = FilePlaylistImporter.MAX_FILE_BYTES + 1
        register(uri, zeros(size))
        try {
            fileImporter().read(ImportSource.File(uri, "gros.csv", "text/csv"))
            fail("exception attendue")
        } catch (e: ImportException) {
            assertEquals(context.getString(com.spautifaille.data.R.string.data_import_error_file_too_large), e.message)
            assertEquals("Ce fichier est trop volumineux (20 Mo maximum).", e.message)
        }
    }

    @Test
    fun `file importer accepts a file of exactly 20 MB in size terms`() = runTest {
        val uri = "content://test.provider/doc/4"
        register(uri, zeros(FilePlaylistImporter.MAX_FILE_BYTES))
        try {
            fileImporter().read(ImportSource.File(uri, "limite.csv", "text/csv"))
            fail("exception attendue")
        } catch (e: ImportException) {
            // Refusé pour son contenu (que des zéros), pas pour sa taille.
            assertTrue(e.message != context.getString(com.spautifaille.data.R.string.data_import_error_file_too_large))
        }
    }

    @Test
    fun `file importer reports unreadable files`() = runTest {
        val uri = "content://test.provider/doc/5"
        register(
            uri,
            object : InputStream() {
                override fun read(): Int = throw IOException("disque")
            },
        )
        try {
            fileImporter().read(ImportSource.File(uri, "x.csv", null))
            fail("exception attendue")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("lire"))
        }
    }

    // --- Registre ------------------------------------------------------------------------------------

    @Test
    fun `registry picks the first importer able to handle the source`() {
        val a = FakeImporter(handles = { it is ImportSource.Url }) { emptyList() }
        val b = FakeImporter(handles = { true }) { emptyList() }
        val registry = PlaylistImporters(listOf(a, b))
        assertSame(a, registry.find(ImportSource.Url(url)))
        assertSame(b, registry.find(ImportSource.File("content://x", null, null)))
        assertNull(PlaylistImporters(listOf(a)).find(ImportSource.File("content://x", null, null)))
    }

    private fun zeros(size: Long): InputStream = object : InputStream() {
        private var remaining = size
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            java.util.Arrays.fill(b, off, off + n, 0)
            remaining -= n
            return n
        }
    }
}
