package com.spautifaille.data.importer.parser

import java.text.Normalizer
import java.util.Locale

/** Champs d'un titre reconnus dans un en-tête CSV ou une clé JSON. */
enum class ImportField { TITLE, ARTIST, ALBUM, DURATION, ISRC, YOUTUBE }

/**
 * Reconnaissance des noms de colonnes (CSV) / clés (JSON) : normalisation (BOM, guillemets, casse,
 * diacritiques, ponctuation) puis comparaison à des listes d'alias EN + FR (+ quelques ES/DE/IT).
 *
 * La comparaison se fait sur la forme « compacte » (sans espaces) pour que `trackName`, `track_name`,
 * `Track Name` et `TRACK-NAME` soient équivalents.
 */
object CsvHeaderMatcher {

    /** `"\uFEFF Titre du morceau (ms)"` → `"titre du morceau ms"`. */
    fun normalize(header: String): String {
        val decomposed = Normalizer.normalize(header, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                ch == '\uFEFF' || ch == '\u200B' -> Unit
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                Character.isLetterOrDigit(ch) -> sb.append(ch.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }

    private fun compact(header: String): String = normalize(header).replace(" ", "")

    private val ALIASES: Map<ImportField, List<String>> = mapOf(
        ImportField.TITLE to listOf(
            "Track Name", "Track Title", "Title", "Titre", "Titre du morceau", "Titre de la chanson",
            "Nom du titre", "Nom du morceau", "Nom de la piste", "Name", "Nom", "Song", "Song Name",
            "Song Title", "Chanson", "Morceau", "Track", "Piste",
            "Titulo", "Nombre", "Cancion", "Nombre de la cancion", "Titel", "Lied", "Songtitel", "Titolo", "Brano", "Canzone",
        ),
        ImportField.ARTIST to listOf(
            "Artist Name(s)", "Artist Name", "Artist Names", "Artist", "Artists", "Artiste", "Artistes",
            "Nom de l'artiste", "Nom des artistes", "Interprète", "Interprètes",
            "Artista", "Artistas", "Künstler", "Interpret", "Performer", "Band",
        ),
        ImportField.ALBUM to listOf(
            "Album Name", "Album", "Nom de l'album", "Titre de l'album", "Album Title", "Albumtitel", "Nombre del album", "Nome album",
        ),
        ImportField.DURATION to listOf(
            "Track Duration (ms)", "Duration (ms)", "Duration", "Duration ms", "Duration in ms", "Duration millis",
            "Duration milliseconds", "Duration (s)", "Duration s", "Duration sec", "Duration secs", "Duration seconds",
            "Duration in seconds", "Track Duration", "Track Length", "Length", "Length ms", "Runtime", "Time",
            "Durée", "Durée (ms)", "Durée (s)", "Durée en secondes", "Durée en ms", "Durée du morceau", "Temps",
            "Duración", "Duracion ms", "Dauer", "Durata",
        ),
        ImportField.ISRC to listOf("ISRC", "Code ISRC", "Track ISRC"),
        ImportField.YOUTUBE to listOf(
            "Video ID", "Video Id", "YouTube ID", "YouTube Video ID", "YouTube URL", "YouTube Link", "Video URL", "Video Link",
            "URL", "Link", "Youtube", "Lien", "Lien YouTube", "URL de la vidéo", "Lien de la vidéo", "ID de la vidéo",
            "ID vidéo", "Identifiant de la vidéo", "Enlace", "URL del vídeo", "ID del vídeo", "Video-Link", "Link zum Video",
        ),
    ).mapValues { (_, list) -> list.map(::compact).distinct() }

    /** Champ correspondant à [header], ou null. */
    fun fieldOf(header: String): ImportField? {
        val key = compact(header)
        return ALIASES.entries.firstOrNull { key in it.value }?.key
    }

    /**
     * Associe chaque champ à l'index de colonne. Un alias plus haut dans la liste l'emporte (ex. `Track Name`
     * avant `Name`), à alias égal la colonne la plus à gauche. Une colonne n'est attribuée qu'à un seul champ.
     */
    fun resolve(headers: List<String>): Map<ImportField, Int> {
        val compacted = headers.map(::compact)
        val used = mutableSetOf<Int>()
        val result = LinkedHashMap<ImportField, Int>()
        for (field in ImportField.entries) {
            for (alias in ALIASES.getValue(field)) {
                val idx = compacted.indices.firstOrNull { it !in used && compacted[it] == alias } ?: continue
                result[field] = idx
                used += idx
                break
            }
        }
        return result
    }

    /** Unité indiquée par le nom de colonne (`Duration (ms)`, `durationSeconds`, `Durée (s)`), ou null. */
    fun durationUnitHint(header: String): DurationUnit? {
        val tokens = normalize(header).split(' ')
        val key = compact(header)
        return when {
            tokens.any { it == "ms" || it.startsWith("milli") } || key.endsWith("ms") -> DurationUnit.MILLISECONDS
            tokens.any { it == "s" || it == "sec" || it == "secs" || it.startsWith("second") } ||
                key.contains("second") || key.endsWith("sec") -> DurationUnit.SECONDS
            else -> null
        }
    }
}

enum class DurationUnit { MILLISECONDS, SECONDS }

/** Conversion des durées textuelles (`225000`, `225`, `3:45`, `1:02:03`, `PT3M45S`) en millisecondes. */
object DurationParser {

    private val ISO = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$", RegexOption.IGNORE_CASE)
    private val THOUSANDS = Regex("^\\d{1,3}([, ]\\d{3})+$")

    /** Valeur numérique brute (`225000`, `225.5`, `225,5`), ou null si ce n'est pas un nombre. */
    private fun numeric(raw: String): Double? {
        val s = raw.trim().replace(' ', ' ')
        if (s.isEmpty()) return null
        if (THOUSANDS.matches(s)) return s.filter { it.isDigit() }.toDoubleOrNull()
        return s.replace(',', '.').toDoubleOrNull()
    }

    private fun clock(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.trim().replace(',', '.').toDoubleOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        val seconds = when (nums.size) {
            2 -> nums[0] * 60 + nums[1]
            else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
        }
        return Math.round(seconds * 1000)
    }

    private fun iso(raw: String): Long? {
        val m = ISO.matchEntire(raw.trim()) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return Math.round((h * 3600 + min * 60 + s) * 1000)
    }

    /**
     * Convertit toute une colonne. Sans [unitHint], l'unité numérique est déduite de la médiane des valeurs :
     * ≥ 5000 → millisecondes (un titre de 5 s en ms ≈ 5000), sinon secondes. Les formats `mm:ss` et ISO 8601
     * sont toujours interprétés comme tels. Valeurs vides / illisibles / ≤ 0 → null.
     */
    fun parseColumn(values: List<String>, unitHint: DurationUnit?): List<Long?> {
        val unit = unitHint ?: run {
            val nums = values.mapNotNull { v -> if (':' in v) null else numeric(v) }.filter { it > 0 }.sorted()
            if (nums.isNotEmpty() && nums[nums.size / 2] >= 5000) DurationUnit.MILLISECONDS else DurationUnit.SECONDS
        }
        return values.map { parseWith(it, unit) }
    }

    /** Valeur isolée, sans contexte de colonne (heuristique : ≥ 10000 → ms). */
    fun parse(raw: String, unitHint: DurationUnit? = null): Long? {
        val v = numeric(raw)
        val unit = unitHint ?: if (v != null && v >= 10_000) DurationUnit.MILLISECONDS else DurationUnit.SECONDS
        return parseWith(raw, unit)
    }

    private fun parseWith(raw: String, unit: DurationUnit): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val ms = clock(s) ?: iso(s) ?: numeric(s)?.let {
            Math.round(if (unit == DurationUnit.MILLISECONDS) it else it * 1000)
        }
        return ms?.takeIf { it > 0 }
    }
}

/** Extraction d'identifiants de vidéo YouTube (11 caractères) depuis un id nu ou une URL. */
object YoutubeIds {

    private val BARE = Regex("^[A-Za-z0-9_-]{11}$")
    private val TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}")
    private const val ID = "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"
    private val WATCH = Regex("(?i:(?:youtube(?:-nocookie)?\\.com|music\\.youtube\\.com)/watch\\?(?:[^#\\s]*&)?v=)$ID")
    private val PATH = Regex("(?i:youtube(?:-nocookie)?\\.com/(?:embed|shorts|live|v)/)$ID")
    private val SHORT = Regex("(?i:youtu\\.be/)$ID")

    fun isVideoId(s: String): Boolean = BARE.matches(s)

    /**
     * Vrai pour un id qui ressemble à un id aléatoire plutôt qu'à un mot de 11 lettres (« Radioactive ») :
     * contient un chiffre, `_`, `-` ou une majuscule autre qu'en première lettre.
     */
    fun looksRandom(s: String): Boolean =
        isVideoId(s) && (s.any { it.isDigit() || it == '_' || it == '-' } || s.drop(1).any { it.isUpperCase() })

    fun looksLikeTimestamp(s: String): Boolean = TIMESTAMP.containsMatchIn(s.trim())

    /**
     * Id nu (`dQw4w9WgXcQ`) ou URL `watch?v=`, `youtu.be/`, `music.youtube.com/watch?v=`, `/shorts/`, `/embed/`,
     * `/live/`, `m.youtube.com`… Renvoie null si aucun id n'est trouvé (ex. URL de playlist seule).
     */
    fun extract(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (isVideoId(s)) return s
        return (WATCH.find(s) ?: PATH.find(s) ?: SHORT.find(s))?.groupValues?.get(1)
    }
}
