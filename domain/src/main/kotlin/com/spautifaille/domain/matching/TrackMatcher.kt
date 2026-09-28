package com.spautifaille.domain.matching

import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.MatchCandidate
import com.spautifaille.domain.importer.MatchResult
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Détail d'un score (voir [TrackMatcher.explain]). Les composantes valent `null` quand l'information manque
 * d'un côté (durée, artiste) : son poids est alors redistribué.
 */
data class ScoreBreakdown(
    /** Similarité de titre étirée dans [0, 1]. */
    val title: Double,
    /** Similarité d'artiste étirée dans [0, 1], `null` si inconnue. */
    val artist: Double?,
    /** Proximité de durée dans [0, 1], `null` si une des durées manque. */
    val duration: Double?,
    /** Facteur multiplicatif (≤ 1) lié aux marqueurs de version. */
    val versionFactor: Double,
    val albumBonus: Double,
    val total: Double,
)

/**
 * Moteur de matching titre importé → résultat YouTube.
 *
 * Flux côté data : chercher `buildQuery(imported)` dans YouTube Music (« songs »), appeler [match] sur les ~10
 * premiers résultats, puis retomber sur la recherche vidéo classique si le statut est [MatchStatus.NOT_FOUND].
 *
 * ### Score ([score]) — dans [0, 1]
 * `total = clamp( base × shrink × versionFactor + albumBonus )` avec
 * - `base` = moyenne pondérée des composantes disponibles :
 *   - **titre 0.45** : titre nettoyé (décorations, `feat.`, préfixe « Artiste - » retirés), similarité
 *     `(0.5·JaroWinkler + 0.5·tokenSetRatio) × √(ratio de longueurs)`, étirée : 0 en dessous de 0.4, 1 à 1 ;
 *   - **artiste 0.30** : meilleure correspondance entre les artistes importés (le principal compte plein, les
 *     autres ×0.92) et les noms côté candidat (chaîne sans `VEVO`/`Topic`, préfixe `Artiste -` du titre ×0.97,
 *     invités `feat.` du titre ×0.97), ou artiste importé cité dans le titre du candidat (0.9) ; étirée :
 *     0 en dessous de 0.55 ;
 *   - **durée 0.25** : 1 si |Δ| ≤ 3 s, décroissance linéaire jusqu'à 0 à 30 s ;
 * - `shrink = 0.7 + 0.3 × (poids disponible)` : moins d'indices → jamais de match automatique sur le seul
 *   titre (durée absente : ×0.925 ; titre seul : ×0.835) ;
 * - `versionFactor` : marqueur de version présent chez le candidat mais pas dans l'import ×0.55 (le premier,
 *   ×0.85 pour chaque suivant ; `radio edit` ×0.8), marqueur de l'import absent chez le candidat ×0.75
 *   (`radio edit` ×0.9) ;
 * - `albumBonus` = +0.03 si les albums sont identiques une fois normalisés.
 *
 * @param autoThreshold score minimal pour [MatchStatus.MATCHED]
 * @param reviewThreshold score minimal pour [MatchStatus.NEEDS_REVIEW]
 */
class TrackMatcher(
    val autoThreshold: Double = 0.85,
    val reviewThreshold: Double = 0.60,
) {
    init {
        require(reviewThreshold in 0.0..1.0 && autoThreshold in 0.0..1.0 && reviewThreshold <= autoThreshold) {
            "Seuils invalides : review=$reviewThreshold auto=$autoThreshold"
        }
    }

    /**
     * Requête de recherche : `"artistePrincipal titreNettoyé"`. Le titre perd ses décorations (`Official Video`,
     * `Remastered 2011`...) et sa partie `feat.`, mais garde les marqueurs de version (`Remix`, `Live`...).
     */
    fun buildQuery(t: ImportedTrack): String {
        val title = cleanTitle(t.title)
        val flat = FLATTEN_BRACKETS.replace(title, " ").let { FLATTEN_DASH.replace(it, " ") }
            .replace(Regex("""\s+"""), " ").trim().ifBlank { title }
        val artist = t.primaryArtist?.trim().orEmpty()
        return "$artist $flat".trim()
    }

    /** Score de [candidate] pour [imported], dans [0, 1] (voir la doc de classe pour les poids). */
    fun score(imported: ImportedTrack, candidate: Track): Double = explain(imported, candidate).total

    /** Comme [score], avec le détail des composantes. */
    fun explain(imported: ImportedTrack, candidate: Track): ScoreBreakdown = explain(prepare(imported), candidate)

    /**
     * Score tous les [candidates], trie par score décroissant (égalités : proximité de durée, puis ordre d'origine)
     * et décide du statut : MATCHED (≥ autoThreshold), NEEDS_REVIEW (≥ reviewThreshold), sinon NOT_FOUND —
     * dans ce cas le meilleur est quand même renvoyé s'il existe. `alternatives` = les 3 suivants au score ≥ 0.3.
     *
     * À n'appeler que si `imported.youtubeId == null` : quand l'id YouTube est connu il n'y a rien à matcher
     * (l'appelant construit directement le résultat), cet id est ignoré ici.
     */
    fun match(imported: ImportedTrack, candidates: List<Track>): MatchResult {
        if (candidates.isEmpty()) return MatchResult(MatchStatus.NOT_FOUND, null, emptyList())
        val prepared = prepare(imported)
        val ranked = candidates.distinctBy { it.id }.mapIndexed { index, track ->
            val b = explain(prepared, track)
            Ranked(track, b.total, b.duration ?: -1.0, index)
        }.sortedWith(
            compareByDescending<Ranked> { Math.round(it.score * 1e9) }
                .thenByDescending { it.durationCloseness }
                .thenBy { it.index },
        )
        val best = ranked.first()
        val status = when {
            best.score >= autoThreshold -> MatchStatus.MATCHED
            best.score >= reviewThreshold -> MatchStatus.NEEDS_REVIEW
            else -> MatchStatus.NOT_FOUND
        }
        val alternatives = ranked.drop(1).filter { it.score >= ALTERNATIVE_MIN_SCORE }.take(MAX_ALTERNATIVES)
            .map { MatchCandidate(it.track, it.score) }
        return MatchResult(status, MatchCandidate(best.track, best.score), alternatives)
    }

    // ---------------------------------------------------------------------------------------------

    private class Ranked(val track: Track, val score: Double, val durationCloseness: Double, val index: Int)

    private class Prepared(
        val titleNorm: String,
        /** Tous les artistes (importés + invités du titre), chacun avec ses variantes normalisées. */
        val artists: List<List<String>>,
        val primaryIndex: Int,
        val tags: Set<String>,
        val durationMs: Long?,
        val albumCompact: String?,
    )

    private fun cleanTitle(title: String): String =
        TextNormalizer.extractFeaturing(TextNormalizer.stripDecorations(title)).title

    private fun prepare(t: ImportedTrack): Prepared {
        val f = TextNormalizer.extractFeaturing(TextNormalizer.stripDecorations(t.title))
        val names = (t.artists.filter { it.isNotBlank() } + f.featured).distinctBy { TextNormalizer.compact(it) }
        val variants = names.map { nameVariants(it, split = false) }.filter { it.isNotEmpty() }
        return Prepared(
            titleNorm = TextNormalizer.normalize(f.title).ifEmpty { TextNormalizer.normalize(t.title) },
            artists = variants,
            primaryIndex = 0,
            tags = TextNormalizer.versionTags(t.title),
            durationMs = t.durationMs,
            albumCompact = t.album?.let { TextNormalizer.compact(it) }?.takeIf { it.isNotEmpty() },
        )
    }

    /** Variantes normalisées d'un nom : entier, hors/dans les parenthèses, et (si [split]) chaque co-artiste. */
    private fun nameVariants(name: String, split: Boolean): List<String> {
        val raw = linkedSetOf(name)
        PAREN.findAll(name).forEach { raw += it.groupValues[1] }
        val outside = PAREN.replace(name, " ").trim()
        if (outside.isNotEmpty()) raw += outside
        if (split) raw.toList().forEach { raw += TextNormalizer.splitArtists(it) }
        return raw.map { TextNormalizer.normalizeArtist(it) }.filter { it.isNotEmpty() }.distinct()
    }

    private fun explain(imp: Prepared, c: Track): ScoreBreakdown {
        val stripped = TextNormalizer.stripDecorations(c.title)
        val feat = TextNormalizer.extractFeaturing(stripped)
        var candTitle = feat.title

        // Noms d'artiste côté candidat : chaîne (poids 1) puis noms lus dans le titre (poids 0.97).
        val channel = if (c.artist.isBlank()) emptyList() else nameVariants(c.artist, split = true)
        val fromTitle = mutableListOf<String>()
        feat.featured.forEach { fromTitle += nameVariants(it, split = false) }

        // « Artiste - Titre » (ou « Titre - Artiste ») posté par une chaîne quelconque.
        splitArtistFromTitle(candTitle, imp)?.let { (rest, artistText) ->
            candTitle = rest
            fromTitle += nameVariants(artistText, split = true)
        }

        val title = titleScore(imp.titleNorm, TextNormalizer.normalize(candTitle))
        val artist = artistScore(imp, channel, fromTitle, TextNormalizer.tokens(c.title))
        val duration = durationCloseness(imp.durationMs, c.durationMs)

        var sum = W_TITLE * title
        var available = W_TITLE
        if (artist != null) { sum += W_ARTIST * artist; available += W_ARTIST }
        if (duration != null) { sum += W_DURATION * duration; available += W_DURATION }
        val base = sum / available * (SHRINK_FLOOR + (1.0 - SHRINK_FLOOR) * available)

        val version = versionFactor(imp.tags, candidateTags(c))
        val album = if (imp.albumCompact != null && c.album != null && TextNormalizer.compact(c.album) == imp.albumCompact) ALBUM_BONUS else 0.0
        val total = (base * version + album).coerceIn(0.0, 1.0)
        return ScoreBreakdown(title, artist, duration, version, album, total)
    }

    private fun candidateTags(c: Track): Set<String> {
        val tags = TextNormalizer.versionTags(c.title).toMutableSet()
        // Les chaînes « Karaoke », « Nightcore » signalent la version même si le titre ne le dit pas.
        TextNormalizer.versionTags(c.artist).filterTo(tags) { it == "karaoke" || it == "nightcore" }
        return tags
    }

    private fun versionFactor(imported: Set<String>, candidate: Set<String>): Double {
        var f = 1.0
        (candidate - imported).forEachIndexed { i, tag ->
            f *= if (i == 0) (if (tag == "radio edit") 0.8 else 0.55) else 0.85
        }
        (imported - candidate).forEach { tag -> f *= if (tag == "radio edit") 0.9 else 0.75 }
        return f
    }

    // --- titre -----------------------------------------------------------------------------------

    private fun titleScore(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return stretch(fuzzy(a, b), TITLE_FLOOR)
    }

    /** Similarité mixte [0, 1] de deux chaînes normalisées ; pénalise l'écart de longueur (mots en trop). */
    private fun fuzzy(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ca = a.replace(" ", "")
        val cb = b.replace(" ", "")
        if (ca == cb) return 1.0
        val blend = 0.5 * StringSimilarity.jaroWinkler(a, b) + 0.5 * StringSimilarity.tokenSetRatio(a, b)
        val lengthRatio = min(ca.codePointCount(0, ca.length), cb.codePointCount(0, cb.length)).toDouble() /
            max(ca.codePointCount(0, ca.length), cb.codePointCount(0, cb.length))
        return blend * sqrt(lengthRatio)
    }

    private fun stretch(s: Double, floor: Double) = ((s - floor) / (1.0 - floor)).coerceIn(0.0, 1.0)

    /** Reconnaît un préfixe / suffixe « Artiste » séparé par un tiret ; renvoie (titre restant, texte de l'artiste). */
    private fun splitArtistFromTitle(text: String, imp: Prepared): Pair<String, String>? {
        val seps = TITLE_SEP.findAll(text).toList()
        if (seps.isEmpty() || imp.artists.isEmpty()) return null
        val first = seps.first()
        val left = text.substring(0, first.range.first).trim()
        val rest = text.substring(first.range.last + 1).trim()
        if (left.isNotEmpty() && rest.isNotEmpty() && looksLikeImportedArtist(left, imp)) return rest to left
        val last = seps.last()
        val right = text.substring(last.range.last + 1).trim()
        val head = text.substring(0, last.range.first).trim()
        if (right.isNotEmpty() && head.isNotEmpty() && looksLikeImportedArtist(right, imp)) return head to right
        return null
    }

    private fun looksLikeImportedArtist(text: String, imp: Prepared): Boolean {
        val parts = nameVariants(text, split = true)
        return imp.artists.any { variants -> variants.any { v -> parts.any { p -> artistSimilarity(v, p) >= ARTIST_IN_TITLE_MIN } } }
    }

    // --- artiste ---------------------------------------------------------------------------------

    private fun artistSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ca = a.replace(" ", "")
        val cb = b.replace(" ", "")
        if (ca == cb) return 1.0
        val da = dropThe(a)
        val db = dropThe(b)
        if (da.replace(" ", "") == db.replace(" ", "")) return 1.0
        return fuzzy(da, db)
    }

    private fun dropThe(s: String): String {
        if (s.startsWith("the ") && s.length > 6) return s.substring(4)
        val compact = s.replace(" ", "")
        return if (!s.contains(' ') && compact.startsWith("the") && compact.length > 6) compact.substring(3) else s
    }

    private fun artistScore(imp: Prepared, channel: List<String>, fromTitle: List<String>, titleTokens: List<String>): Double? {
        if (imp.artists.isEmpty()) return null
        fun best(variants: List<String>): Double {
            var b = 0.0
            for (v in variants) {
                for (n in channel) b = max(b, artistSimilarity(v, n))
                for (n in fromTitle) b = max(b, artistSimilarity(v, n) * FROM_TITLE_DISCOUNT)
                if (v.length >= 3 && containsTokens(titleTokens, v.split(' '))) b = max(b, MENTION_SCORE)
            }
            return b
        }

        val primary = best(imp.artists[imp.primaryIndex])
        var others = 0.0
        for (i in imp.artists.indices) if (i != imp.primaryIndex) others = max(others, best(imp.artists[i]))
        val raw = max(primary, others * OTHER_ARTIST_DISCOUNT)
        // Aucun nom d'artiste côté candidat et aucune mention dans le titre : information absente, pas un désaccord.
        if (raw == 0.0 && channel.isEmpty() && fromTitle.isEmpty()) return null
        return stretch(raw, ARTIST_FLOOR)
    }

    private fun containsTokens(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (i in 0..haystack.size - needle.size) {
            var ok = true
            for (j in needle.indices) if (haystack[i + j] != needle[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }

    // --- durée -----------------------------------------------------------------------------------

    private fun durationCloseness(a: Long?, b: Long?): Double? {
        if (a == null || b == null || a <= 0 || b <= 0) return null
        val d = abs(a - b)
        return when {
            d <= FULL_DURATION_MS -> 1.0
            d >= ZERO_DURATION_MS -> 0.0
            else -> 1.0 - (d - FULL_DURATION_MS).toDouble() / (ZERO_DURATION_MS - FULL_DURATION_MS)
        }
    }

    private companion object {
        const val W_TITLE = 0.45
        const val W_ARTIST = 0.30
        const val W_DURATION = 0.25
        const val SHRINK_FLOOR = 0.7
        const val ALBUM_BONUS = 0.03
        const val TITLE_FLOOR = 0.4
        const val ARTIST_FLOOR = 0.55
        const val FROM_TITLE_DISCOUNT = 0.97
        const val OTHER_ARTIST_DISCOUNT = 0.92
        const val MENTION_SCORE = 0.9
        const val ARTIST_IN_TITLE_MIN = 0.88
        const val FULL_DURATION_MS = 3_000L
        const val ZERO_DURATION_MS = 30_000L
        const val MAX_ALTERNATIVES = 3
        const val ALTERNATIVE_MIN_SCORE = 0.3

        val PAREN = Regex("""[(\[（]([^)\]）]*)[)\]）]""")
        val TITLE_SEP = Regex("""\s+[-–—―|]\s+|\s*[–—―]\s*""")
        val FLATTEN_BRACKETS = Regex("""[(\[{)\]}（）【】［］〔〕]""")
        val FLATTEN_DASH = Regex("""\s+[-–—―|]\s+""")
    }
}
