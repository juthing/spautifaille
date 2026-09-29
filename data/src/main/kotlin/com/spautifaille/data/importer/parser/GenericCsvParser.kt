package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack

/**
 * CSV quelconque (`,` `;` ou tabulation) dont l'en-tête contient au moins une colonne titre
 * ou une colonne id/URL YouTube (alias EN/FR/ES/DE/IT : voir [CsvHeaderMatcher]).
 * Durées : ms, secondes ou `mm:ss` / `h:mm:ss` (auto-détecté). Artistes multiples séparés par `;` ou ` / `.
 */
class GenericCsvParser : PlaylistFileParser {
    override val format = ImportFormat.GENERIC_CSV

    override fun canParse(fileName: String?, content: String): Boolean {
        if (!ImportFileNames.extensionNotIn(fileName, "json")) return false
        val start = content.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (start.startsWith("{") || start.startsWith("[")) return false
        val rows = CsvRows.readOrNull(content) ?: return false
        return findHeader(rows) != null
    }

    override fun parse(fileName: String?, content: String): List<ImportedPlaylist> {
        val rows = CsvRows.read(content)
        val (headerIndex, columns) = findHeader(rows)
            ?: throw ImportParseException("Aucune colonne « titre » ou « lien YouTube » n'a été reconnue dans ce CSV.")
        val header = rows[headerIndex]
        val data = rows.drop(headerIndex + 1)
        val titleCol = columns[ImportField.TITLE]
        val artistCol = columns[ImportField.ARTIST]
        val albumCol = columns[ImportField.ALBUM]
        val durationCol = columns[ImportField.DURATION]
        val isrcCol = columns[ImportField.ISRC]
        val youtubeCol = columns[ImportField.YOUTUBE]

        val durations = DurationParser.parseColumn(
            data.map { it.cell(durationCol) },
            durationCol?.let { CsvHeaderMatcher.durationUnitHint(header[it]) },
        )
        val tracks = data.mapIndexedNotNull { i, row ->
            TrackSanitizer.clean(
                ImportedTrack(
                    title = row.cell(titleCol),
                    artists = splitArtistsConservative(row.cell(artistCol)),
                    album = row.cell(albumCol),
                    durationMs = durations[i],
                    isrc = row.cell(isrcCol),
                    youtubeId = YoutubeIds.extract(row.cell(youtubeCol)),
                ),
            )
        }
        val name = ImportFileNames.playlistName(fileName, fallback = "Playlist importée")
        return listOf(ImportedPlaylist(name, tracks, format))
    }

    /** Première ligne (parmi les 10 premières) qui expose une colonne titre ou YouTube. */
    private fun findHeader(rows: List<List<String>>): Pair<Int, Map<ImportField, Int>>? {
        for (i in 0 until minOf(rows.size, 10)) {
            val columns = CsvHeaderMatcher.resolve(rows[i])
            if (ImportField.TITLE in columns || ImportField.YOUTUBE in columns) return i to columns
        }
        return null
    }
}
