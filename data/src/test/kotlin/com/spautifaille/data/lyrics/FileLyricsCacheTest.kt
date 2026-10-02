package com.spautifaille.data.lyrics

import com.spautifaille.domain.lyrics.LyricLine
import com.spautifaille.domain.lyrics.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLyricsCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000_000L
    private fun cache(dir: File = File(tmp.root, "lyrics")) = FileLyricsCache(dir) { now }

    private val synced = Lyrics.Synced(listOf(LyricLine(1_000, "a"), LyricLine(5_000, "")), "LRCLIB")

    @Test fun missQuandAbsent() {
        assertNull(cache().read("abc"))
    }

    @Test fun hitSynchronisees() {
        val c = cache()
        c.write("abc", synced)
        assertEquals(synced, c.read("abc")!!.lyrics)
    }

    @Test fun hitBrutesInstrumentalEtNegatif() {
        val c = cache()
        c.write("p", Lyrics.Plain("texte\nsuite", "LRCLIB"))
        c.write("i", Lyrics.Instrumental("LRCLIB"))
        c.write("n", null)
        assertEquals(Lyrics.Plain("texte\nsuite", "LRCLIB"), c.read("p")!!.lyrics)
        assertEquals(Lyrics.Instrumental("LRCLIB"), c.read("i")!!.lyrics)
        val negative = c.read("n")
        assertNotNull("le résultat négatif doit être mis en cache", negative)
        assertNull(negative!!.lyrics)
    }

    @Test fun resultatNegatifExpireApresSeptJours() {
        val c = cache()
        c.write("n", null)
        now += FileLyricsCache.NOT_FOUND_TTL_MS - 1
        assertNotNull(c.read("n"))
        now += 1
        assertNull(c.read("n"))
        assertFalse("le fichier expiré est supprimé", File(tmp.root, "lyrics/n.json").exists())
    }

    @Test fun synchroniseesExpirentApresTrenteJoursEtBrutesApresSept() {
        val c = cache()
        c.write("s", synced)
        c.write("p", Lyrics.Plain("x"))
        now += FileLyricsCache.NOT_FOUND_TTL_MS
        assertNotNull(c.read("s"))
        assertNull(c.read("p"))
        now += FileLyricsCache.FOUND_TTL_MS - FileLyricsCache.NOT_FOUND_TTL_MS
        assertNull(c.read("s"))
    }

    @Test fun fichierCorrompuCompteCommeAbsent() {
        val dir = File(tmp.root, "lyrics").apply { mkdirs() }
        File(dir, "abc.json").writeText("{pas du json")
        val c = cache(dir)
        assertNull(c.read("abc"))
        assertFalse(File(dir, "abc.json").exists())
    }

    @Test fun ecraseUneEntreeExistante() {
        val c = cache()
        c.write("abc", null)
        c.write("abc", synced)
        assertEquals(synced, c.read("abc")!!.lyrics)
    }

    @Test fun identifiantAvecCaracteresInterditsResteDansLeDossier() {
        val c = cache()
        c.write("../../evil/x", synced)
        assertNotNull(c.read("../../evil/x"))
        val files = File(tmp.root, "lyrics").listFiles()!!
        assertEquals(1, files.size)
        assertFalse(files.single().name.contains('/'))
    }
}
