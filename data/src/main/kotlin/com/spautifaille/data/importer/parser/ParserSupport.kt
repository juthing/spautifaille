package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportedTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.StringReader

/** Lecture CSV tolérante partagée : lignes brutes (sans en-tête imposé), cellules nettoyées. */
internal object CsvRows {

    /**
     * Lit [content] avec le séparateur détecté. Les lignes entièrement vides sont ignorées, les cellules
     * sont `trim`ées (BOM compris). Lève [ImportParseException] si les guillemets sont mal formés.
     */
    fun read(content: String, delimiter: Char = ImportTextDecoder.sniffDelimiter(content)): List<List<String>> {
        val format = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .setQuote('"')
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(false)
            .get()
        return try {
            CSVParser.parse(StringReader(content.removePrefix("﻿")), format).use { parser ->
                parser.records
                    .map { record -> record.map { it.replace("﻿", "").trim() } }
                    .filter { row -> row.any { it.isNotEmpty() } }
            }
        } catch (e: Exception) {
            throw ImportParseException("Le fichier CSV est mal formé (guillemets non fermés ou contenu corrompu).", e)
        }
    }

    /** Comme [read] mais renvoie null au lieu de lever (pour `canParse`). */
    fun readOrNull(content: String): List<List<String>>? = try {
        read(content)
    } catch (_: ImportParseException) {
        null
    }
}

internal fun List<String>.cell(index: Int?): String = if (index == null) "" else getOrNull(index).orEmpty()

internal object ImportJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseOrNull(content: String): JsonElement? = try {
        json.parseToJsonElement(content.removePrefix("﻿").trim())
    } catch (_: Exception) {
        null
    }

    fun parse(content: String): JsonElement = try {
        json.parseToJsonElement(content.removePrefix("﻿").trim())
    } catch (e: Exception) {
        throw ImportParseException("Le fichier JSON est mal formé ou corrompu.", e)
    }
}

internal object ImportFileNames {

    /** Extension en minuscules sans point (`""` s'il n'y en a pas ou si fileName est null). */
    fun extension(fileName: String?): String {
        val base = baseName(fileName) ?: return ""
        val dot = base.lastIndexOf('.')
        return if (dot in 1 until base.length - 1 && base.length - dot <= 6) base.substring(dot + 1).lowercase() else ""
    }

    private fun baseName(fileName: String?): String? =
        fileName?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotEmpty() }

    /** Nom de fichier sans chemin ni extension connue (`Ma_playlist.csv` → `Ma_playlist`). */
    fun stem(fileName: String?): String? {
        val base = baseName(fileName) ?: return null
        val ext = extension(base)
        val stem = if (ext.isNotEmpty()) base.dropLast(ext.length + 1) else base
        return stem.trim().takeIf { it.isNotEmpty() }
    }

    /** Nom de playlist depuis le fichier, underscores → espaces (convention Exportify). */
    fun playlistName(fileName: String?, fallback: String, underscoresToSpaces: Boolean = true): String {
        val stem = stem(fileName) ?: return fallback
        val name = if (underscoresToSpaces) stem.replace('_', ' ') else stem
        return name.replace(Regex("\\s+"), " ").trim().ifEmpty { fallback }
    }

    fun pathSegments(fileName: String?): List<String> =
        fileName.orEmpty().split('/', '\\').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Faux si l'extension est explicitement celle d'un autre type ([rejected]) ; l'extension n'est qu'un indice. */
    fun extensionNotIn(fileName: String?, vararg rejected: String): Boolean = extension(fileName) !in rejected
}

internal object TrackSanitizer {
    /** Nettoie les champs, retire les artistes vides, renvoie null si ni titre ni id YouTube. */
    fun clean(track: ImportedTrack): ImportedTrack? {
        val title = track.title.trim()
        val youtubeId = track.youtubeId?.trim()?.takeIf { it.isNotEmpty() }
        if (title.isEmpty() && youtubeId == null) return null
        return track.copy(
            title = title,
            artists = track.artists.map { it.trim() }.filter { it.isNotEmpty() },
            album = track.album?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = track.durationMs?.takeIf { it > 0 },
            isrc = track.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            youtubeId = youtubeId,
        )
    }
}

/** Découpe une cellule « artistes » : séparateurs `;` et ` / ` (jamais la virgule seule : « Tyler, The Creator »). */
internal fun splitArtistsConservative(raw: String): List<String> =
    raw.split(';', ' ').flatMap { it.split(" / ") }.map { it.replace("\\,", ",").trim() }.filter { it.isNotEmpty() }

/** Découpe sur les virgules non échappées (`\,` = virgule dans un nom), format Exportify. */
internal fun splitExportifyArtists(raw: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length && raw[i + 1] == ',') {
            current.append(',')
            i += 2
            continue
        }
        if (c == ',') {
            out += current.toString().trim()
            current.clear()
        } else {
            current.append(c)
        }
        i++
    }
    out += current.toString().trim()
    return out.filter { it.isNotEmpty() }
}
