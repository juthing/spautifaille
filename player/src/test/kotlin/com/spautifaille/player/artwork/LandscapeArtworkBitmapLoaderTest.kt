package com.spautifaille.player.artwork

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class LandscapeArtworkBitmapLoaderTest {

    private val id = "dQw4w9WgXcQ"
    private val maxres = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    private val sd = "https://i.ytimg.com/vi/$id/sddefault.jpg"
    private val hq = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    private val thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg?sqp=abc"

    private fun bitmap(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** Délégué factice : URL -> bitmap ; absente de la table = 404. */
    private class FakeDelegate(private val images: Map<String, Bitmap>) : BitmapLoader {
        val requested = mutableListOf<String>()
        var decoded = 0

        override fun supportsMimeType(mimeType: String) = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            decoded++
            return Futures.immediateFuture(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            requested += uri.toString()
            val image = images[uri.toString()]
            return if (image != null) Futures.immediateFuture(image) else Futures.immediateFailedFuture(FileNotFoundException("404 $uri"))
        }
    }

    private fun loader(delegate: BitmapLoader, cacheSize: Int = 4, compose: (Bitmap) -> Bitmap = { it }) =
        LandscapeArtworkBitmapLoader(delegate, Dispatchers.Unconfined, compose, cacheSize)

    private fun metadata(url: String?) = MediaMetadata.Builder().setArtworkUri(url?.let(Uri::parse)).build()

    private fun LandscapeArtworkBitmapLoader.load(url: String?): Bitmap? =
        loadBitmapFromMetadata(metadata(url))?.get(5, TimeUnit.SECONDS)

    @Test
    fun `maxresdefault est essayee d abord et composee`() {
        val source = bitmap(1280, 720)
        val composed = bitmap(1280, 720)
        val delegate = FakeDelegate(mapOf(maxres to source))
        val result = loader(delegate, compose = { if (it === source) composed else error("source inattendue") }).load(thumb)
        assertSame(composed, result)
        assertEquals(listOf(maxres), delegate.requested)
    }

    @Test
    fun `repli sur sddefault puis hqdefault quand les meilleures sources sont absentes`() {
        val source = bitmap(480, 360)
        val delegate = FakeDelegate(mapOf(hq to source))
        loader(delegate).load(thumb)
        assertEquals(listOf(maxres, sd, hq), delegate.requested)
    }

    @Test
    fun `une source minuscule - placeholder YouTube - est ignoree`() {
        val placeholder = bitmap(120, 90)
        val good = bitmap(640, 480)
        val delegate = FakeDelegate(mapOf(maxres to placeholder, sd to good))
        val result = loader(delegate).load(thumb)
        assertSame(good, result)
        assertEquals(listOf(maxres, sd), delegate.requested)
    }

    @Test
    fun `pochette googleusercontent - version 1200 px d abord`() {
        val url = "https://lh3.googleusercontent.com/abc=w226-h226-l90-rj"
        val hd = "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj"
        val delegate = FakeDelegate(mapOf(hd to bitmap(1200, 1200)))
        loader(delegate).load(url)
        assertEquals(listOf(hd), delegate.requested)
    }

    @Test
    fun `aucune source exploitable - repli sur le chargement de artworkUri d origine`() {
        val original = bitmap(100, 100)
        // Toutes les candidates échouent sauf l'URL d'origine, demandée une dernière fois par le repli.
        val delegate = FakeDelegate(mapOf(thumb to original))
        val loader = loader(delegate)
        // thumb est lui-même une candidate (dernière) : 100x100 est trop petit, le repli la renvoie telle quelle.
        val result = loader.load(thumb)
        assertSame(original, result)
        assertEquals(listOf(maxres, sd, hq, thumb, thumb), delegate.requested)
    }

    @Test
    fun `le resultat est mis en cache et reutilise`() {
        val delegate = FakeDelegate(mapOf(maxres to bitmap(1280, 720)))
        var composed = 0
        val loader = loader(delegate, compose = { composed++; bitmap(1280, 720) })
        val first = loader.load(thumb)
        val second = loader.load(thumb)
        assertSame(first, second)
        assertEquals(1, composed)
        assertEquals(listOf(maxres), delegate.requested)
    }

    @Test
    fun `le cache est borne - le plus ancien est evince`() {
        val ids = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc")
        val images = ids.associate { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" to bitmap(1280, 720) }
        val delegate = FakeDelegate(images)
        val loader = loader(delegate, cacheSize = 2)
        ids.forEach { loader.load("https://i.ytimg.com/vi/$it/hqdefault.jpg") }
        delegate.requested.clear()
        loader.load("https://i.ytimg.com/vi/${ids[2]}/hqdefault.jpg") // en cache
        assertEquals(emptyList<String>(), delegate.requested)
        loader.load("https://i.ytimg.com/vi/${ids[0]}/hqdefault.jpg") // évincé : rechargé
        assertEquals(listOf("https://i.ytimg.com/vi/${ids[0]}/maxresdefault.jpg"), delegate.requested)
    }

    @Test
    fun `un echec n est pas mis en cache`() {
        val delegate = FakeDelegate(emptyMap())
        val loader = loader(delegate)
        runCatching { loader.load(thumb) } // tout échoue, y compris le repli
        val after = delegate.requested.size
        runCatching { loader.load(thumb) }
        assertEquals(after * 2, delegate.requested.size)
    }

    @Test
    fun `sans artwork le chargeur renvoie null et artworkData est decode tel quel`() {
        val delegate = FakeDelegate(emptyMap())
        val loader = loader(delegate)
        assertNull(loader.loadBitmapFromMetadata(MediaMetadata.EMPTY))
        loader.loadBitmapFromMetadata(MediaMetadata.Builder().setArtworkData(byteArrayOf(1, 2, 3), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build())!!.get()
        assertEquals(1, delegate.decoded)
        assertEquals(emptyList<String>(), delegate.requested)
    }
}
