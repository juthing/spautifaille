package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportedPlaylist

/**
 * Registre ordonné des parsers de fichiers : décode les octets puis délègue au premier parser dont
 * [PlaylistFileParser.canParse] est vrai. L'ordre va du plus spécifique au plus tolérant.
 */
class PlaylistFileParsers(
    private val parsers: List<PlaylistFileParser> = defaultParsers(),
) {
    /** Parser choisi pour ce contenu, ou null. */
    fun detect(fileName: String?, content: String): PlaylistFileParser? =
        parsers.firstOrNull { runCatching { it.canParse(fileName, content) }.getOrDefault(false) }

    fun parse(fileName: String?, bytes: ByteArray): List<ImportedPlaylist> =
        parse(fileName, ImportTextDecoder.decode(bytes))

    /**
     * @throws ImportParseException fichier vide, format non reconnu, corrompu, ou sans aucun titre exploitable.
     */
    fun parse(fileName: String?, content: String): List<ImportedPlaylist> {
        if (content.isBlank()) throw ImportParseException("Le fichier est vide.")
        val parser = detect(fileName, content) ?: throw ImportParseException(UNRECOGNIZED)
        val playlists = try {
            parser.parse(fileName, content)
        } catch (e: ImportParseException) {
            throw e
        } catch (e: Exception) {
            throw ImportParseException("Impossible de lire ce fichier (${parser.format}).", e)
        }
        return playlists.filter { it.tracks.isNotEmpty() }
            .ifEmpty { throw ImportParseException("Aucun titre exploitable n'a été trouvé dans ce fichier.") }
    }

    companion object {
        const val UNRECOGNIZED =
            "Format de fichier non reconnu. Formats pris en charge : export Exportify (CSV), export de données " +
                "Spotify (JSON), playlist Google Takeout YouTube (CSV), ou CSV / JSON avec des colonnes titre et artiste."

        fun defaultParsers(): List<PlaylistFileParser> = listOf(
            ExportifyCsvParser(),
            SpotifyJsonParser(),
            TakeoutCsvParser(),
            GenericCsvParser(),
            GenericJsonParser(),
        )
    }
}
