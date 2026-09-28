package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack

/**
 * CSV de playlist YouTube issu de Google Takeout (`Takeout/YouTube et YouTube Music/playlists/<nom>-videos.csv`).
 *
 * Format actuel : `Video ID,Playlist Video Creation Timestamp` (en-têtes localisés selon la langue du compte).
 * Ancien format : lignes de métadonnées (`Playlist ID,Channel ID,Time Created,...,Title,...` + une ligne de valeurs),
 * ligne vide, puis `Video ID,Time Added`.
 *
 * Le fichier ne contient que des ids : chaque piste est produite avec `title = ""` et `youtubeId` renseigné ;
 * la couche data récupère le titre / l'artiste via l'id (pas de matching). Détection de la colonne : en-tête
 * « Video ID » connu (multi-langues), sinon repli positionnel (première colonne = ids de 11 caractères, suivie
 * d'un horodatage ISO ou fichier situé sous un dossier `playlists`).
 */
class TakeoutCsvParser : PlaylistFileParser {
    override val format = ImportFormat.TAKEOUT_CSV

    private data class Layout(val idColumn: Int, val dataStart: Int, val metadataTitle: String?)

    override fun canParse(fileName: String?, content: String): Boolean {
        if (!ImportFileNames.extensionNotIn(fileName, "json")) return false
        val rows = CsvRows.readOrNull(content) ?: return false
        return findLayout(fileName, rows) != null
    }

    override fun parse(fileName: String?, content: String): List<ImportedPlaylist> {
        val rows = CsvRows.read(content)
        val layout = findLayout(fileName, rows)
            ?: throw ImportParseException("Ce fichier n'est pas un CSV de playlist Google Takeout (colonne « ID de la vidéo » introuvable).")
        val tracks = rows.drop(layout.dataStart).mapNotNull { row ->
            val id = YoutubeIds.extract(row.cell(layout.idColumn)) ?: return@mapNotNull null
            ImportedTrack(title = "", youtubeId = id)
        }
        val name = layout.metadataTitle
            ?: ImportFileNames.playlistName(fileName, fallback = "Playlist YouTube", underscoresToSpaces = false)
                .replace(VIDEOS_SUFFIX, "").trim().ifEmpty { "Playlist YouTube" }
        return listOf(ImportedPlaylist(name, tracks, format))
    }

    private fun findLayout(fileName: String?, rows: List<List<String>>): Layout? {
        val scanLimit = minOf(rows.size, MAX_SCAN_ROWS)

        // 1) En-tête « Video ID » reconnu.
        for (i in 0 until scanLimit) {
            val row = rows[i]
            val col = row.indexOfFirst { CsvHeaderMatcher.normalize(it) in VIDEO_ID_HEADERS }
            if (col < 0) continue
            // Un CSV « Video ID, Title, Artist… » relève du CSV générique (les titres y sont utiles).
            if (row.any { CsvHeaderMatcher.fieldOf(it) == ImportField.TITLE }) return null
            return Layout(col, dataStart = i + 1, metadataTitle = metadataTitle(rows, i))
        }

        // 2) Repli positionnel : en-tête localisé inconnu.
        val underPlaylistsDir = ImportFileNames.pathSegments(fileName).dropLast(1).any { it == "playlists" || it == "playlist" }
        for (i in 0 until minOf(scanLimit, 6)) {
            val sample = rows.drop(i).take(5)
            if (sample.isEmpty()) break
            val idsOnly = sample.all { YoutubeIds.isVideoId(it.cell(0)) }
            if (!idsOnly) continue
            val allTimestamped = sample.all { it.size >= 2 && YoutubeIds.looksLikeTimestamp(it.cell(1)) }
            val plausible = allTimestamped || (underPlaylistsDir && sample.all { it.size <= 3 }) ||
                (sample.size >= 2 && sample.all { it.size == 1 && YoutubeIds.looksRandom(it.cell(0)) })
            if (plausible) return Layout(0, dataStart = i, metadataTitle = metadataTitle(rows, i))
        }
        return null
    }

    /** Ancien format : ligne `Playlist ID,...,Title,...` suivie de ses valeurs, avant l'en-tête `Video ID`. */
    private fun metadataTitle(rows: List<List<String>>, videoHeaderIndex: Int): String? {
        for (i in 0 until videoHeaderIndex - 1) {
            val header = rows[i]
            if (CsvHeaderMatcher.normalize(header.cell(0)) !in PLAYLIST_ID_HEADERS) continue
            val col = header.indexOfFirst { CsvHeaderMatcher.normalize(it) in PLAYLIST_TITLE_HEADERS }
            if (col < 0) return null
            return rows.getOrNull(i + 1)?.cell(col)?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private companion object {
        const val MAX_SCAN_ROWS = 30
        val VIDEOS_SUFFIX = Regex("(?i)-vid[eé]os?$")

        // Anglais (vérifié) + traductions probables (non vérifiées hors zh-TW « 影片 ID »).
        val VIDEO_ID_HEADERS = setOf(
            "video id", "videoid", "id de la video", "id video", "id del video", "id de video",
            "identifiant de la video", "video kennung", "video ids", "影片 id", "视频 id", "動画 id", "동영상 id",
        )
        val PLAYLIST_ID_HEADERS = setOf("playlist id", "id de la playlist", "id de la liste de lecture", "id de playlist")
        val PLAYLIST_TITLE_HEADERS = setOf(
            "title", "playlist title", "playlist title original", "titre", "titre de la playlist",
            "titre de la liste de lecture", "playlist title (original)",
        ).map { CsvHeaderMatcher.normalize(it) }.toSet()
    }
}
