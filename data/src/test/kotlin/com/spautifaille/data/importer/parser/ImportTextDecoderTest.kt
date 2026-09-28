package com.spautifaille.data.importer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTextDecoderTest {

    @Test
    fun `decodes plain UTF-8`() {
        assertEquals("Été 夜 🎧", ImportTextDecoder.decode("Été 夜 🎧".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `strips UTF-8 BOM`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Titre;Artiste".toByteArray()
        assertEquals("Titre;Artiste", ImportTextDecoder.decode(bytes))
    }

    @Test
    fun `decodes UTF-16 LE with BOM fixture`() {
        val text = ImportFixtures.text("generic_fr_utf16le_bom.csv")
        assertEquals(ImportFixtures.text("generic_fr_semicolon_utf8.csv"), text)
        assertTrue(text.startsWith("Titre;Artiste;Album;Durée"))
    }

    @Test
    fun `decodes UTF-16 BE with BOM`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Titre;Durée".toByteArray(Charsets.UTF_16BE)
        assertEquals("Titre;Durée", ImportTextDecoder.decode(bytes))
    }

    @Test
    fun `decodes UTF-16 LE without BOM`() {
        assertEquals("Titre;Artiste\nA;B", ImportTextDecoder.decode("Titre;Artiste\nA;B".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun `decodes UTF-16 BE without BOM`() {
        assertEquals("Titre;Artiste\nA;B", ImportTextDecoder.decode("Titre;Artiste\nA;B".toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `falls back to Windows-1252 when bytes are not valid UTF-8`() {
        val text = ImportFixtures.text("generic_fr_cp1252.csv")
        assertTrue(text.contains("Céline Dion"))
        assertTrue(text.contains("Où êtes-vous ?"))
        assertTrue(text.contains("Mélancolie « œuvre »"))
        assertTrue(text.contains("Naïve – Vol. 2"))
    }

    @Test
    fun `Windows-1252 euro sign and curly quotes`() {
        val bytes = byteArrayOf(0x80.toByte(), 0x20, 0x93.toByte(), 0x78, 0x94.toByte())
        assertEquals("€ “x”", ImportTextDecoder.decode(bytes))
    }

    @Test
    fun `empty input gives empty string`() {
        assertEquals("", ImportTextDecoder.decode(ByteArray(0)))
    }

    @Test
    fun `sniffs semicolon delimiter`() {
        assertEquals(';', ImportTextDecoder.sniffDelimiter("Titre;Artiste;Album;Durée\nA;B;C;3:00"))
    }

    @Test
    fun `sniffs tab delimiter`() {
        assertEquals('\t', ImportTextDecoder.sniffDelimiter("Title\tArtist\tAlbum\nA\tB\tC"))
    }

    @Test
    fun `sniffs comma delimiter and ignores delimiters inside quotes`() {
        assertEquals(',', ImportTextDecoder.sniffDelimiter("Title,Artist,Album"))
        assertEquals(';', ImportTextDecoder.sniffDelimiter("\"a,b,c,d\";x;y"))
    }

    @Test
    fun `defaults to comma without delimiter and skips leading blank lines and BOM`() {
        assertEquals(',', ImportTextDecoder.sniffDelimiter("Titre"))
        assertEquals(',', ImportTextDecoder.sniffDelimiter(""))
        assertEquals(';', ImportTextDecoder.sniffDelimiter("﻿\n\nTitre;Artiste"))
    }
}
