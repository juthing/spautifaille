package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack

/**
 * Export CSV d'[Exportify](https://github.com/watsonbox/exportify) (Spotify).
 *
 * En-têtes courants : `Track URI,Track Name,Artist URI(s),Artist Name(s),Album URI,Album Name,...,
 * Track Duration (ms),...,ISRC,...,Added By,Added At` ; anciennes versions : `Spotify ID`, `Artist Name`,
 * `Duration (ms)`. Les artistes multiples sont joints par `, ` et les virgules d'un nom échappées `\,`.
 * Le fichier porte le nom de la playlist (underscores pour les espaces).
 */
class ExportifyCsvParser : PlaylistFileParser {
    override val format = ImportFormat.EXPORTIFY_CSV

    override fun canParse(fileName: String?, content: String): Boolean {
        if (!ImportFileNames.extensionNotIn(fileName, "json")) return false
        val header = CsvRows.readOrNull(content)?.firstOrNull() ?: return false
        return isExportifyHeader(header)
    }

    private fun isExportifyHeader(header: List<String>): Boolean {
        val normalized = header.map { CsvHeaderMatcher.normalize(it) }
        val hasSpotifyId = normalized.any { it in SPOTIFY_ID_HEADERS }
        val hasTrackName = normalized.any { it == "track name" }
        return hasSpotifyId && hasTrackName
    }

    override fun parse(fileName: String?, content: String): List<ImportedPlaylist> {
        val rows = CsvRows.read(content)
        val header = rows.firstOrNull()?.takeIf(::isExportifyHeader)
            ?: throw ImportParseException("Ce fichier n'est pas un export Exportify valide (colonnes « Track Name » / « Track URI » absentes).")
        val columns = CsvHeaderMatcher.resolve(header)
        val titleCol = columns[ImportField.TITLE]
        val artistCol = columns[ImportField.ARTIST]
        val albumCol = columns[ImportField.ALBUM]
        val durationCol = columns[ImportField.DURATION]
        val isrcCol = columns[ImportField.ISRC]

        val data = rows.drop(1)
        val durations = DurationParser.parseColumn(
            data.map { it.cell(durationCol) },
            // Exportify exprime toujours la durée en ms ; le repli sur l'en-tête ne sert qu'aux variantes exotiques.
            durationCol?.let { CsvHeaderMatcher.durationUnitHint(header[it]) } ?: DurationUnit.MILLISECONDS,
        )
        val tracks = data.mapIndexedNotNull { i, row ->
            TrackSanitizer.clean(
                ImportedTrack(
                    title = row.cell(titleCol),
                    artists = splitExportifyArtists(row.cell(artistCol)),
                    album = row.cell(albumCol),
                    durationMs = durations[i],
                    isrc = row.cell(isrcCol),
                ),
            )
        }
        val name = ImportFileNames.playlistName(fileName, fallback = "Playlist Spotify")
        return listOf(ImportedPlaylist(name, tracks, format))
    }

    private companion object {
        val SPOTIFY_ID_HEADERS = setOf("track uri", "spotify id", "spotify uri")
    }
}
