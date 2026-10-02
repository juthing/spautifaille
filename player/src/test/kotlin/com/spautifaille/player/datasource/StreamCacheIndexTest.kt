package com.spautifaille.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.domain.model.AudioQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class StreamCacheIndexTest {

    private lateinit var dir: File
    private lateinit var cache: SimpleCache

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("media-cache").toFile()
        cache = SimpleCache(dir, NoOpCacheEvictor(), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))
    }

    @After
    fun tearDown() {
        cache.release()
        dir.deleteRecursively()
    }

    /** Écrit [length] octets à partir de [position] pour [key] ; [contentLength] = longueur totale connue (ou non). */
    private fun write(key: String, position: Long, length: Int, contentLength: Long? = null) {
        val hole = cache.startReadWrite(key, position, length.toLong())!!
        val file = cache.startFile(key, position, length.toLong())
        file.writeBytes(ByteArray(length) { it.toByte() })
        cache.commitFile(file, length.toLong())
        cache.releaseHoleSpan(hole)
        if (contentLength != null) {
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, contentLength)
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    private fun playable(quality: AudioQuality = AudioQuality.BEST) = StreamCacheIndex.playableVideoIds(cache, quality)

    @Test
    fun `fully cached track is playable even when tiny`() {
        write(StreamCacheKeys.of("full", AudioQuality.BEST), 0, 1_000, contentLength = 1_000)
        assertEquals(setOf("full"), playable())
    }

    @Test
    fun `partially cached track is playable only above the minimum prefix`() {
        write(StreamCacheKeys.of("big", AudioQuality.BEST), 0, StreamCacheIndex.MIN_PARTIAL_BYTES.toInt(), contentLength = 5_000_000)
        write(StreamCacheKeys.of("small", AudioQuality.BEST), 0, 10_000, contentLength = 5_000_000)
        assertEquals(setOf("big"), playable())
    }

    @Test
    fun `cached bytes that do not start at zero are not playable`() {
        write(StreamCacheKeys.of("middle", AudioQuality.BEST), 1_000_000, StreamCacheIndex.MIN_PARTIAL_BYTES.toInt(), contentLength = 5_000_000)
        assertTrue(playable().isEmpty())
    }

    @Test
    fun `entries of another quality are ignored`() {
        write(StreamCacheKeys.of("a", AudioQuality.BEST), 0, 1_000, contentLength = 1_000)
        write(StreamCacheKeys.of("b", AudioQuality.DATA_SAVER), 0, 1_000, contentLength = 1_000)
        assertEquals(setOf("a"), playable(AudioQuality.BEST))
        assertEquals(setOf("b"), playable(AudioQuality.DATA_SAVER))
    }

    @Test
    fun `key parsing`() {
        assertEquals("abc", StreamCacheKeys.videoIdOrNull("abc#BEST", AudioQuality.BEST))
        assertNull(StreamCacheKeys.videoIdOrNull("abc#BEST", AudioQuality.DATA_SAVER))
        assertNull(StreamCacheKeys.videoIdOrNull("#BEST", AudioQuality.BEST))
        assertNull(StreamCacheKeys.videoIdOrNull("abc", AudioQuality.BEST))
    }

    @Test
    fun `a fully cached track plays through the cache without ever opening the upstream`() {
        val key = StreamCacheKeys.of("offline1", AudioQuality.BEST)
        write(key, 0, 4_096, contentLength = 4_096)
        var upstreamOpened = false
        val upstream = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long {
                    upstreamOpened = true
                    throw IOException("no network")
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
                override fun getUri(): Uri? = null
                override fun close() = Unit
            }
        }
        // Même configuration de clé que PlayerDataSourceFactory : URI stable -> clé `<id>#<qualité>`.
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(CacheKeyFactory { spec ->
                TrackUri.videoId(spec.uri)?.let { StreamCacheKeys.of(it, AudioQuality.BEST) } ?: spec.uri.toString()
            })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        val total = source.open(DataSpec(TrackUri.build("offline1")))
        val bytes = ByteArray(8_192)
        var read = 0
        while (true) {
            val n = source.read(bytes, read, bytes.size - read)
            if (n == C.RESULT_END_OF_INPUT) break
            read += n
        }
        source.close()

        assertEquals(4_096L, total)
        assertEquals(4_096, read)
        assertFalse("le cache complet ne doit pas ouvrir la source amont (donc pas de résolution d'URL)", upstreamOpened)
    }
}
