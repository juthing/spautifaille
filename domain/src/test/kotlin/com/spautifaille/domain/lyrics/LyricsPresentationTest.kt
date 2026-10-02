package com.spautifaille.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsPresentationTest {

    private val lines = listOf(
        LyricLine(10_000, "a"),
        LyricLine(20_000, "b"),
        LyricLine(30_000, "c"),
    )

    @Test
    fun `indexAt avant la premiere ligne`() {
        assertEquals(-1, lines.indexAt(0))
        assertEquals(-1, lines.indexAt(9_999))
    }

    @Test
    fun `indexAt aux bornes et entre deux lignes`() {
        assertEquals(0, lines.indexAt(10_000))
        assertEquals(0, lines.indexAt(19_999))
        assertEquals(1, lines.indexAt(20_000))
        assertEquals(2, lines.indexAt(999_999))
    }

    @Test
    fun `indexAt sur liste vide`() {
        assertEquals(-1, emptyList<LyricLine>().indexAt(5_000))
    }

    @Test
    fun `les pauses courtes sont masquees et les longues conservees`() {
        val raw = listOf(
            LyricLine(1_000, "a"),
            LyricLine(5_000, ""), // 1 s avant la suite : masquee
            LyricLine(6_000, "b"),
            LyricLine(8_000, ""), // 12 s : conservee
            LyricLine(20_000, "c"),
        )
        assertEquals(
            listOf(LyricLine(1_000, "a"), LyricLine(6_000, "b"), LyricLine(8_000, ""), LyricLine(20_000, "c")),
            raw.displayLines(),
        )
    }

    @Test
    fun `une longue introduction ajoute une pause initiale`() {
        val display = lines.displayLines()
        assertEquals(LyricLine(0, ""), display.first())
        assertEquals(4, display.size)
        assertEquals(0, display.indexAt(5_000))
    }

    @Test
    fun `pas de pause initiale si la premiere ligne arrive tot ou est deja une pause`() {
        assertEquals(1, listOf(LyricLine(1_000, "a")).displayLines().size)
        val withBreak = listOf(LyricLine(0, ""), LyricLine(15_000, "a")).displayLines()
        assertEquals(listOf(LyricLine(0, ""), LyricLine(15_000, "a")), withBreak)
    }

    @Test
    fun `liste vide`() {
        assertEquals(emptyList<LyricLine>(), emptyList<LyricLine>().displayLines())
    }
}
