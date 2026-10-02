package com.spautifaille.domain.lyrics

import com.spautifaille.domain.matching.StringSimilarity
import com.spautifaille.domain.matching.TextNormalizer
import kotlin.math.abs

/** Résultat brut d'un fournisseur de paroles (ex. une entrée LRCLIB). */
data class LyricsCandidate(
    val trackName: String,
    val artistName: String,
    val durationSec: Double?,
    val instrumental: Boolean = false,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val source: String = Lyrics.SOURCE_LRCLIB,
) {
    /**
     * Paroles exploitables : synchronisées si le LRC contient au moins une ligne de texte, sinon brutes,
     * sinon « instrumental » ; `null` si la fiche est vide.
     */
    fun toLyrics(allowSynced: Boolean = true): Lyrics? {
        if (allowSynced) {
            syncedLyrics?.takeIf { it.isNotBlank() }?.let { raw ->
                val lines = LrcParser.parse(raw)
                if (lines.any { !it.isBreak }) return Lyrics.Synced(lines, source)
            }
        }
        plainLyrics?.takeIf { it.isNotBlank() }?.let { return Lyrics.Plain(it.trim(), source) }
        return if (instrumental) Lyrics.Instrumental(source) else null
    }
}

/**
 * Choisit la meilleure fiche parmi les résultats d'une recherche.
 *
 * Écartés : fiches vides, titre ou artiste sans rapport avec la requête, durée trop éloignée (au-delà de
 * [MAX_SYNCED_DURATION_DIFF_SEC] pour des paroles synchronisées — décalage quasi certain —, de
 * [MAX_PLAIN_DURATION_DIFF_SEC] pour des paroles brutes). Classement : durée à ±[CLOSE_DURATION_SEC] d'abord,
 * puis synchronisées > brutes > instrumental, puis durée la plus proche, puis ressemblance du titre.
 */
object LyricsMatcher {
    const val CLOSE_DURATION_SEC = 3.0
    const val MAX_SYNCED_DURATION_DIFF_SEC = 10.0
    const val MAX_PLAIN_DURATION_DIFF_SEC = 30.0
    private const val MIN_TITLE_SIMILARITY = 0.6
    private const val MIN_ARTIST_SIMILARITY = 0.5

    fun select(query: LyricsQuery, candidates: List<LyricsCandidate>): Lyrics? {
        data class Scored(val lyrics: Lyrics, val bucket: Int, val kind: Int, val diff: Double, val title: Double)

        val scored = candidates.mapNotNull { c ->
            val titleSim = titleSimilarity(query.trackName, c.trackName)
            if (titleSim < MIN_TITLE_SIMILARITY) return@mapNotNull null
            if (!sameArtist(query, c.artistName)) return@mapNotNull null
            val diff = if (query.durationSec != null && c.durationSec != null) {
                abs(query.durationSec - c.durationSec)
            } else {
                null
            }
            // Une version synchronisée trop éloignée en durée est probablement décalée : on se rabat sur le texte brut.
            val lyrics = c.toLyrics(allowSynced = diff == null || diff <= MAX_SYNCED_DURATION_DIFF_SEC)
                ?: return@mapNotNull null
            if (diff != null && lyrics !is Lyrics.Synced && diff > MAX_PLAIN_DURATION_DIFF_SEC) return@mapNotNull null
            val kind = when (lyrics) {
                is Lyrics.Synced -> 0
                is Lyrics.Plain -> 1
                is Lyrics.Instrumental -> 2
            }
            Scored(lyrics, if (diff != null && diff <= CLOSE_DURATION_SEC) 0 else 1, kind, diff ?: 0.0, titleSim)
        }
        return scored.minWithOrNull(
            compareBy<Scored>({ it.bucket }, { it.kind }, { it.diff }, { -it.title }),
        )?.lyrics
    }

    private fun titleSimilarity(wanted: String, found: String): Double {
        val a = TextNormalizer.compact(wanted)
        val b = TextNormalizer.compact(found)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        // « The Weeknd - Blinding Lights » (titre complet saisi comme nom de piste) contient le titre cherché.
        if (a.length >= 4 && b.contains(a)) return 0.9
        if (b.length >= 4 && a.contains(b)) return 0.9
        return StringSimilarity.ratio(TextNormalizer.normalize(wanted), TextNormalizer.normalize(found))
    }

    private fun sameArtist(query: LyricsQuery, found: String): Boolean {
        val wanted = listOf(query.artistName, query.primaryArtist).map { TextNormalizer.compact(it) }.filter { it.isNotEmpty() }
        val candidate = TextNormalizer.compact(found)
        if (wanted.isEmpty() || candidate.isEmpty()) return true
        if (wanted.any { it == candidate || (it.length >= 3 && candidate.contains(it)) || (candidate.length >= 3 && it.contains(candidate)) }) {
            return true
        }
        val foundArtists = TextNormalizer.splitArtists(found).map { TextNormalizer.compact(it) }
        if (wanted.any { it in foundArtists }) return true
        return StringSimilarity.ratio(TextNormalizer.normalizeArtist(query.primaryArtist), TextNormalizer.normalizeArtist(found)) >=
            MIN_ARTIST_SIMILARITY
    }
}
