package com.spautifaille.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parse des horodatages simples`() {
        val lines = LrcParser.parse("[00:12.50]Bonjour\n[01:02.00]Au revoir")
        assertEquals(listOf(LyricLine(12_500, "Bonjour"), LyricLine(62_000, "Au revoir")), lines)
    }

    @Test
    fun `fraction a 1, 2 ou 3 chiffres et absente`() {
        val lines = LrcParser.parse("[00:01.5]a\n[00:02.25]b\n[00:03.123]c\n[00:04]d\n[00:05:50]e")
        assertEquals(listOf(1_500L, 2_250L, 3_123L, 4_000L, 5_500L), lines.map { it.timeMs })
    }

    @Test
    fun `minutes superieures a 59`() {
        assertEquals(3_723_000L, LrcParser.parse("[62:03.00]long").single().timeMs)
    }

    @Test
    fun `plusieurs horodatages sur une meme ligne produisent plusieurs lignes triees`() {
        val lines = LrcParser.parse("[00:30.00][01:10.00]Refrain\n[00:10.00]Couplet")
        assertEquals(
            listOf(LyricLine(10_000, "Couplet"), LyricLine(30_000, "Refrain"), LyricLine(70_000, "Refrain")),
            lines,
        )
    }

    @Test
    fun `offset positif avance les paroles et offset negatif les retarde`() {
        assertEquals(9_500L, LrcParser.parse("[offset:+500]\n[00:10.00]a").single().timeMs)
        assertEquals(10_500L, LrcParser.parse("[offset:-500]\n[00:10.00]a").single().timeMs)
    }

    @Test
    fun `offset place apres les lignes est applique aussi et ne descend pas sous zero`() {
        val lines = LrcParser.parse("[00:00.20]a\n[00:10.00]b\n[offset:1000]")
        assertEquals(listOf(0L, 9_000L), lines.map { it.timeMs })
    }

    @Test
    fun `metadonnees ignorees`() {
        val lrc = """
            [ar:Daft Punk]
            [ti:Around the World]
            [al:Homework]
            [by:someone]
            [length: 03:49]
            [00:05.00]Around the world
        """.trimIndent()
        assertEquals(listOf(LyricLine(5_000, "Around the world")), LrcParser.parse(lrc))
    }

    @Test
    fun `balise de metadonnee suivie d un horodatage sur la meme ligne`() {
        assertEquals(listOf(LyricLine(1_000, "x")), LrcParser.parse("[ar:A][00:01.00]x"))
    }

    @Test
    fun `lignes vides conservees comme pauses et fusionnees`() {
        val lines = LrcParser.parse("[00:05.00]a\n[00:10.00]\n[00:11.00]   \n[00:20.00]b")
        assertEquals(
            listOf(LyricLine(5_000, "a"), LyricLine(10_000, ""), LyricLine(20_000, "b")),
            lines,
        )
        assertTrue(lines[1].isBreak)
    }

    @Test
    fun `pauses de fin retirees`() {
        val lines = LrcParser.parse("[00:05.00]a\n[00:10.00]")
        assertEquals(listOf(LyricLine(5_000, "a")), lines)
    }

    @Test
    fun `balises de mots enhanced retirees`() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>Hel<00:01.40>lo <00:01.80>you")
        assertEquals("Hello you", lines.single().text)
    }

    @Test
    fun `lignes sans horodatage et fins de ligne windows`() {
        val lines = LrcParser.parse("﻿[00:01.00]a\r\nblabla sans temps\r\n[00:02.00]b\r\n")
        assertEquals(listOf("a", "b"), lines.map { it.text })
    }

    @Test
    fun `texte vide ou sans horodatage`() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("juste du texte\n[ar:x]").isEmpty())
    }

    @Test
    fun `crochets dans le texte`() {
        assertEquals("(refrain) [x2]", LrcParser.parse("[00:01.00](refrain) [x2]").single().text)
    }
}
