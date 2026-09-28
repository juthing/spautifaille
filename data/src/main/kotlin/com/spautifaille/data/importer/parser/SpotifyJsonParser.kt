package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder

/**
 * Export de données Spotify (« Télécharger vos données », dossier `Spotify Account Data`) :
 * - `Playlist1.json` : `{"playlists":[{"name","lastModifiedDate","items":[{"track":{"trackName","artistName",
 *   "albumName","trackUri"},"episode":null,"localTrack":null,"addedDate"}]}]}` ;
 * - `YourLibrary.json` : `{"tracks":[{"artist","album","track","uri"}],...}` → playlist « Titres likés (Spotify) ».
 *
 * Lecture par arbre `JsonElement` (tolérante aux évolutions de schéma) : tous les champs sont optionnels.
 * Les épisodes de podcast sont ignorés. Un titre local (`localTrack`) est conservé s'il a un nom, y compris
 * lorsqu'il faut le déduire de son URI `spotify:local:artiste:album:titre:durée`.
 */
class SpotifyJsonParser : PlaylistFileParser {
    override val format = ImportFormat.SPOTIFY_JSON

    override fun canParse(fileName: String?, content: String): Boolean {
        if (!ImportFileNames.extensionNotIn(fileName, "csv", "tsv")) return false
        if (!content.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("{")) return false
        val root = ImportJson.parseOrNull(content) as? JsonObject ?: return false
        return hasPlaylists(root) || hasLikedTracks(root)
    }

    private fun hasPlaylists(root: JsonObject): Boolean {
        val playlists = root["playlists"] as? JsonArray ?: return false
        if (playlists.isEmpty()) return root.containsKey("playlists") && root.size == 1
        return playlists.any { p -> p is JsonObject && (p["items"] is JsonArray) }
    }

    private fun hasLikedTracks(root: JsonObject): Boolean {
        if (root.containsKey("name")) return false // `{name, tracks}` = JSON générique
        val tracks = root["tracks"] as? JsonArray ?: return false
        val first = tracks.firstOrNull() as? JsonObject ?: return false
        val hasTrack = (first["track"] as? JsonPrimitive)?.isString == true
        return hasTrack && (first.containsKey("artist") || first.containsKey("uri")) &&
            (first.containsKey("album") || first.containsKey("uri"))
    }

    override fun parse(fileName: String?, content: String): List<ImportedPlaylist> {
        val root = ImportJson.parse(content) as? JsonObject
            ?: throw ImportParseException("Le fichier JSON Spotify doit contenir un objet à la racine.")
        val result = mutableListOf<ImportedPlaylist>()

        (root["playlists"] as? JsonArray)?.forEachIndexed { index, element ->
            val playlist = element as? JsonObject ?: return@forEachIndexed
            val items = (playlist["items"] ?: playlist["tracks"]) as? JsonArray ?: return@forEachIndexed
            val name = playlist.string("name") ?: "Playlist Spotify ${index + 1}"
            val tracks = items.mapNotNull { (it as? JsonObject)?.let(::playlistItemToTrack) }
            result += ImportedPlaylist(name, tracks, format)
        }

        (root["tracks"] as? JsonArray)?.let { liked ->
            val tracks = liked.mapNotNull { (it as? JsonObject)?.let(::libraryItemToTrack) }
            result += ImportedPlaylist(LIKED_PLAYLIST_NAME, tracks, format)
        }
        return result
    }

    private fun playlistItemToTrack(item: JsonObject): ImportedTrack? {
        if (item["episode"].isPresent()) return null
        val track = item["track"] as? JsonObject
        if (track != null) {
            val title = track.string("trackName") ?: track.string("name") ?: return localTrack(item)
            return TrackSanitizer.clean(
                ImportedTrack(
                    title = title,
                    artists = listOfNotNull(track.string("artistName") ?: track.string("artist")),
                    album = track.string("albumName") ?: track.string("album"),
                ),
            )
        }
        return localTrack(item)
    }

    private fun localTrack(item: JsonObject): ImportedTrack? {
        val local = item["localTrack"] as? JsonObject ?: return null
        val title = local.string("trackName") ?: local.string("name")
        if (title != null) {
            return TrackSanitizer.clean(
                ImportedTrack(
                    title = title,
                    artists = listOfNotNull(local.string("artistName") ?: local.string("artist")),
                    album = local.string("albumName") ?: local.string("album"),
                ),
            )
        }
        return local.string("uri")?.let(::parseLocalUri)
    }

    /** `spotify:local:Artist:Album:Titre:225` (segments encodés « application/x-www-form-urlencoded »). */
    private fun parseLocalUri(uri: String): ImportedTrack? {
        val parts = uri.split(':')
        if (parts.size < 6 || parts[0] != "spotify" || parts[1] != "local") return null
        fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s).trim()
        val title = decode(parts[4])
        if (title.isEmpty()) return null
        return TrackSanitizer.clean(
            ImportedTrack(
                title = title,
                artists = listOf(decode(parts[2])),
                album = decode(parts[3]),
                durationMs = parts[5].toLongOrNull()?.let { it * 1000 },
            ),
        )
    }

    private fun libraryItemToTrack(item: JsonObject): ImportedTrack? {
        val title = item.string("track") ?: item.string("trackName") ?: return null
        return TrackSanitizer.clean(
            ImportedTrack(
                title = title,
                artists = listOfNotNull(item.string("artist") ?: item.string("artistName")),
                album = item.string("album") ?: item.string("albumName"),
            ),
        )
    }

    private fun JsonElement?.isPresent() = this != null && this !is JsonNull

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val LIKED_PLAYLIST_NAME = "Titres likés (Spotify)"
    }
}
