package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON quelconque, avec les mêmes alias de clés que le CSV ([CsvHeaderMatcher], insensible à la casse et
 * à `camelCase` / `snake_case`) :
 * - tableau de titres `[{"title","artist",...}]` ;
 * - `{"name": "...", "tracks": [...]}` (ou `items` / `songs` / `videos`) ;
 * - `{"playlists": [...]}` ou tableau de playlists.
 * L'artiste peut être une chaîne, un tableau de chaînes ou d'objets `{"name": ...}`.
 */
class GenericJsonParser : PlaylistFileParser {
    override val format = ImportFormat.GENERIC_JSON

    override fun canParse(fileName: String?, content: String): Boolean {
        if (!ImportFileNames.extensionNotIn(fileName, "csv", "tsv")) return false
        val start = content.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!start.startsWith("{") && !start.startsWith("[")) return false
        val root = ImportJson.parseOrNull(content) ?: return false
        return extractPlaylists(root, defaultName = "x").any { it.tracks.isNotEmpty() }
    }

    override fun parse(fileName: String?, content: String): List<ImportedPlaylist> {
        val root = ImportJson.parse(content)
        val defaultName = ImportFileNames.playlistName(fileName, fallback = "Playlist importée")
        return extractPlaylists(root, defaultName)
    }

    private fun extractPlaylists(root: JsonElement, defaultName: String): List<ImportedPlaylist> = when (root) {
        is JsonArray -> {
            val objects = root.filterIsInstance<JsonObject>()
            if (objects.isNotEmpty() && objects.all { trackListOf(it) != null }) {
                objects.mapIndexedNotNull { i, o -> playlistFromObject(o, "$defaultName ${i + 1}") }
            } else {
                listOf(ImportedPlaylist(defaultName, readTracks(root), format))
            }
        }
        is JsonObject -> {
            val nested = root["playlists"] as? JsonArray
            if (nested != null) {
                extractPlaylists(nested, defaultName)
            } else {
                listOfNotNull(playlistFromObject(root, defaultName))
            }
        }
        else -> emptyList()
    }

    private fun playlistFromObject(obj: JsonObject, defaultName: String): ImportedPlaylist? {
        val list = trackListOf(obj) ?: return null
        val name = NAME_KEYS.firstNotNullOfOrNull { key ->
            obj.entries.firstOrNull { compactKey(it.key) == key }?.value.asText()
        } ?: defaultName
        return ImportedPlaylist(name, readTracks(list), format)
    }

    private fun trackListOf(obj: JsonObject): JsonArray? {
        for (key in LIST_KEYS) {
            val entry = obj.entries.firstOrNull { compactKey(it.key) == key } ?: continue
            (entry.value as? JsonArray)?.let { return it }
        }
        return null
    }

    private fun readTracks(array: JsonArray): List<ImportedTrack> {
        val raws = array.mapNotNull { element ->
            var obj = element as? JsonObject ?: return@mapNotNull null
            // Enveloppe `{"track": {...}}`
            (obj.entries.singleOrNull()?.value as? JsonObject)?.let { obj = it }
            readRaw(obj)
        }
        val hint = raws.firstNotNullOfOrNull { it.durationKey }?.let(CsvHeaderMatcher::durationUnitHint)
        val durations = DurationParser.parseColumn(raws.map { it.duration }, hint)
        return raws.mapIndexedNotNull { i, raw ->
            TrackSanitizer.clean(
                ImportedTrack(
                    title = raw.title,
                    artists = raw.artists,
                    album = raw.album,
                    durationMs = durations[i],
                    isrc = raw.isrc,
                    youtubeId = YoutubeIds.extract(raw.youtube),
                ),
            )
        }
    }

    private class RawTrack(
        val title: String,
        val artists: List<String>,
        val album: String,
        val duration: String,
        val durationKey: String?,
        val isrc: String,
        val youtube: String,
    )

    private fun readRaw(obj: JsonObject): RawTrack? {
        val keys = obj.keys.toList()
        val columns = CsvHeaderMatcher.resolve(keys)
        if (ImportField.TITLE !in columns && ImportField.YOUTUBE !in columns) return null
        fun value(field: ImportField): JsonElement? = columns[field]?.let { obj[keys[it]] }
        return RawTrack(
            title = value(ImportField.TITLE).asText().orEmpty(),
            artists = artistsOf(value(ImportField.ARTIST)),
            album = value(ImportField.ALBUM).asText().orEmpty(),
            duration = value(ImportField.DURATION).asText().orEmpty(),
            durationKey = columns[ImportField.DURATION]?.let { keys[it] },
            isrc = value(ImportField.ISRC).asText().orEmpty(),
            youtube = value(ImportField.YOUTUBE).asText().orEmpty(),
        )
    }

    private fun artistsOf(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.flatMap { artistsOf(it) }
        is JsonObject -> listOfNotNull(
            element.entries.firstOrNull { compactKey(it.key) in setOf("name", "artistname", "nom") }?.value.asText(),
        )
        is JsonPrimitive -> if (element is JsonNull) emptyList() else splitArtistsConservative(element.content)
        null -> emptyList()
    }

    private fun JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun compactKey(key: String) = CsvHeaderMatcher.normalize(key).replace(" ", "")

    private companion object {
        val LIST_KEYS = listOf("tracks", "songs", "items", "videos", "titles", "entries", "morceaux", "titres")
        val NAME_KEYS = listOf("name", "title", "playlist", "playlistname", "nom", "titre")
    }
}
