package com.spautifaille.domain.lyrics

import com.spautifaille.domain.matching.TextNormalizer
import com.spautifaille.domain.model.Track
import kotlin.math.roundToInt

/**
 * Requête de paroles dérivée d'un [Track] YouTube, nettoyée des décorations propres à YouTube
 * (`(Official Video)`, `[Lyrics]`, `- Topic`, `VEVO`, `feat.`, préfixe « Artiste - »...).
 *
 * @property artistName artiste complet nettoyé ; [primaryArtist] le premier artiste (`A, B & C` -> `A`),
 * égal à [artistName] quand il n'y en a qu'un.
 */
data class LyricsQuery(
    val trackName: String,
    val artistName: String,
    val primaryArtist: String,
    val albumName: String?,
    val durationSec: Int?,
) {
    /** Texte de recherche libre (`/api/search?q=`). */
    val searchText: String get() = "$primaryArtist $trackName".trim()

    companion object {
        private val ARTIST_SUFFIX = Regex(
            """(?i)\s*(?:[-–—]\s*topic|vevo|\s+official(?:\s+channel)?|\s+channel)\s*$""",
        )
        private val DASH = Regex("""\s+[-–—]\s+""")

        fun from(track: Track): LyricsQuery {
            val artist = cleanArtist(track.artist)
            val primary = TextNormalizer.splitArtists(artist).firstOrNull() ?: artist
            return LyricsQuery(
                trackName = cleanTitle(track.title, artist),
                artistName = artist,
                primaryArtist = primary,
                albumName = track.album?.trim()?.takeIf { it.isNotEmpty() },
                durationSec = track.durationMs?.let { (it / 1000.0).roundToInt() }?.takeIf { it > 0 },
            )
        }

        /** `Daft Punk - Topic` -> `Daft Punk`, `TheWeekndVEVO` -> `TheWeeknd`, `A feat. B` -> `A`. */
        fun cleanArtist(raw: String): String {
            var s = raw.trim()
            var previous: String
            do {
                previous = s
                s = ARTIST_SUFFIX.replace(s, "").trim()
            } while (s != previous)
            s = TextNormalizer.extractFeaturing(s).title
            return s.ifBlank { raw.trim() }
        }

        /**
         * Retire les décorations (`Official Video`, `Lyrics`, `Remastered`...), les invités `(feat. X)` et le
         * préfixe « Artiste - » fréquent dans les titres YouTube. Les marqueurs de version (live, remix...) restent.
         */
        fun cleanTitle(raw: String, artist: String = ""): String {
            var s = TextNormalizer.stripDecorations(raw)
            s = TextNormalizer.extractFeaturing(s).title
            s = stripArtistPrefix(s, artist)
            s = s.trim().trim('"', '“', '”', '«', '»').trim()
            return s.ifBlank { raw.trim() }
        }

        private fun stripArtistPrefix(title: String, artist: String): String {
            if (artist.isBlank()) return title
            val match = DASH.find(title) ?: return title
            val left = title.substring(0, match.range.first)
            val right = title.substring(match.range.last + 1)
            if (right.isBlank()) return title
            val artistKey = TextNormalizer.compact(artist)
            val leftKey = TextNormalizer.compact(left)
            if (artistKey.length < 2 || leftKey.length < 2) return title
            val sameArtist = leftKey == artistKey || leftKey in artistKey || artistKey in leftKey ||
                TextNormalizer.splitArtists(left).any { TextNormalizer.compact(it) == artistKey }
            return if (sameArtist) right else title
        }
    }
}
