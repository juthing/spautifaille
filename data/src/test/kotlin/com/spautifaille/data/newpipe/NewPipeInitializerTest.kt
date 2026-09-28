package com.spautifaille.data.newpipe

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class NewPipeInitializerTest {

    @Test fun initIsIdempotentAndThreadSafe() {
        val downloader = OkHttpDownloader(OkHttpClient())
        val initializer = NewPipeInitializer(downloader)
        assertFalse(initializer.isInitialized)

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map {
            pool.submit {
                start.await()
                initializer.ensureInitialized()
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()

        assertTrue(initializer.isInitialized)
        assertSame(downloader, NewPipe.getDownloader())
        assertEquals("fr", NewPipe.getPreferredLocalization().languageCode)
        assertEquals("FR", NewPipe.getPreferredContentCountry().countryCode)

        initializer.init() // no-op
        assertSame(downloader, NewPipe.getDownloader())
    }
}
