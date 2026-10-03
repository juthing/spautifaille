package com.spautifaille.data.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [TEST_SDK])
class FileLoudnessStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("loudness").toFile()
        file = File(dir, FileLoudnessStore.FILE_NAME)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun TestScope.store(maxEntries: Int = 100) =
        FileLoudnessStore(file, this, StandardTestDispatcher(testScheduler), maxEntries, saveDelayMs = 1_000)

    @Test fun `valeur ecrite lisible aussitot en memoire`() = runTest {
        val store = store()
        assertNull(store.peek("a"))
        store.put("a", 2.5f)
        assertEquals(2.5f, store.peek("a")!!, 0f)
        assertEquals(2.5f, store.get("a")!!, 0f)
    }

    @Test fun `persistance regroupee puis relecture par une nouvelle instance`() = runTest {
        val first = store()
        first.put("a", 1.5f)
        first.put("b", -3.25f)
        first.put("a", 1.75f) // remplace
        assertFalse(file.exists())
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(file.exists())
        assertEquals(listOf("b\t-3.25", "a\t1.75"), file.readLines())

        val second = store()
        assertNull(second.peek("a")) // pas encore chargé : peek ne touche pas au disque
        assertEquals(1.75f, second.get("a")!!, 0f)
        assertEquals(-3.25f, second.get("b")!!, 0f)
        assertNull(second.get("inconnu"))
    }

    @Test fun `une ecriture avant le premier chargement ne perd pas les anciennes entrees`() = runTest {
        file.writeText("ancien\t4.0\n")
        val store = store()
        store.put("nouveau", -1f)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("ancien\t4.0", "nouveau\t-1.0"), file.readLines())
    }

    @Test fun `la valeur recente l emporte sur celle du disque`() = runTest {
        file.writeText("a\t4.0\n")
        val store = store()
        store.put("a", -2f)
        assertEquals(-2f, store.get("a")!!, 0f)
    }

    @Test fun `eviction des plus anciennes entrees`() = runTest {
        val store = store(maxEntries = 3)
        listOf("a", "b", "c", "d").forEach { store.put(it, 1f) }
        assertNull(store.peek("a"))
        assertEquals(1f, store.peek("d")!!, 0f)
        store.put("b", 2f) // « b » redevient la plus récente
        store.put("e", 1f)
        assertEquals(2f, store.peek("b")!!, 0f)
        assertNull(store.peek("c"))
    }

    @Test fun `valeurs invalides ignorees`() = runTest {
        val store = store()
        store.put("a", Float.NaN)
        store.put("b", Float.POSITIVE_INFINITY)
        store.put("", 1f)
        store.put("x\ty", 1f)
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(store.peek("a"))
        assertNull(store.peek("b"))
        assertFalse(file.exists())
    }

    @Test fun `fichier corrompu ignore ligne par ligne`() = runTest {
        file.writeText("bon\t3.0\npas de tabulation\n\tvide\nnan\tNaN\nmauvais\tabc\nautre\t-1.5\n")
        val store = store()
        assertEquals(3f, store.get("bon")!!, 0f)
        assertEquals(-1.5f, store.get("autre")!!, 0f)
        assertNull(store.get("nan"))
        assertNull(store.get("mauvais"))
    }

    @Test fun `ecrire la meme valeur ne reecrit pas le fichier`() = runTest {
        val store = store()
        store.put("a", 1f)
        advanceTimeBy(1_001)
        runCurrent()
        val written = file.lastModified()
        file.setLastModified(written - 10_000)
        val marker = file.lastModified()
        store.put("a", 1f)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(marker, file.lastModified())
    }
}
