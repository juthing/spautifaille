package com.spautifaille.data.importer.parser

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Décodage octets → texte et détection du séparateur CSV. */
object ImportTextDecoder {

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /**
     * UTF-8 (BOM retiré) ; BOM UTF-16 LE/BE (ou UTF-16 sans BOM détecté par les octets nuls) ;
     * à défaut, Windows-1252 si les octets ne sont pas de l'UTF-8 valide (Excel FR « CSV »).
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            looksLikeUtf16WithoutBom(bytes, littleEndian = true) -> String(bytes, Charsets.UTF_16LE)
            looksLikeUtf16WithoutBom(bytes, littleEndian = false) -> String(bytes, Charsets.UTF_16BE)
            else -> decodeUtf8OrWindows1252(bytes)
        }
        return text.removePrefix("﻿")
    }

    private fun decodeUtf8OrWindows1252(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, WINDOWS_1252)
    }

    /** Texte ASCII/latin en UTF-16 sans BOM : un octet sur deux est nul. */
    private fun looksLikeUtf16WithoutBom(bytes: ByteArray, littleEndian: Boolean): Boolean {
        if (bytes.size < 4 || bytes.size % 2 != 0) return false
        val sample = minOf(bytes.size, 200) / 2 * 2
        var zeros = 0
        var nonZeros = 0
        var i = 0
        while (i < sample) {
            val high = bytes[if (littleEndian) i + 1 else i].toInt()
            val low = bytes[if (littleEndian) i else i + 1].toInt()
            if (high == 0 && low != 0) zeros++ else if (high != 0) nonZeros++
            i += 2
        }
        return zeros > 0 && nonZeros == 0
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

    /**
     * Séparateur le plus fréquent (hors guillemets) de la première ligne non vide : `,` `;` ou tabulation.
     * La virgule l'emporte en cas d'égalité ou d'absence de séparateur.
     */
    fun sniffDelimiter(content: String): Char {
        val line = content.removePrefix("﻿").lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var commas = 0
        var semicolons = 0
        var tabs = 0
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                inQuotes -> Unit
                c == ',' -> commas++
                c == ';' -> semicolons++
                c == '\t' -> tabs++
            }
        }
        return when {
            commas >= semicolons && commas >= tabs -> ','
            semicolons >= tabs -> ';'
            else -> '\t'
        }
    }
}
