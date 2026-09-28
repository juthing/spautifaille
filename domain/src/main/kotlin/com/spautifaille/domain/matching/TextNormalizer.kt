package com.spautifaille.domain.matching

import java.text.Normalizer
import java.util.Locale

/** Résultat de [TextNormalizer.extractFeaturing] : titre sans la partie « feat. » et artistes invités. */
data class Featuring(val title: String, val featured: List<String>)

/**
 * Normalisation et nettoyage de texte pour le matching (titres, noms d'artistes).
 *
 * Objet pur (aucune dépendance Android) ; toutes les fonctions sont sans état et thread-safe.
 */
object TextNormalizer {

    // ---------------------------------------------------------------------------------------------
    // Normalisation
    // ---------------------------------------------------------------------------------------------

    /**
     * Forme canonique de comparaison :
     * - NFKC (compatibilité : pleine chasse, ligatures...), minuscules ;
     * - diacritiques retirés **uniquement sur les lettres latines** (é→e, ß→ss, ø→o) ; CJK, cyrillique,
     *   arabe... sont conservés tels quels (les marques d'un script non latin restent attachées) ;
     * - `&`, `+`, `et`, `und`, `and` → jeton commun `and` ;
     * - apostrophes supprimées sans espace (`don't` = `dont`), `$` → `s` et `!` → `i` à l'intérieur d'un mot
     *   (`$uicideboy$` = `suicideboys`, `P!nk` = `pink`), autre ponctuation / emoji / symboles → espace ;
     * - espaces collapsés.
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val src = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val cps = src.codePoints().toArray()
        val out = StringBuilder(cps.size + 8)
        var afterNonLatinLetter = false
        for (i in cps.indices) {
            val cp = cps[i]
            val prev = if (i > 0) cps[i - 1] else -1
            val next = if (i < cps.size - 1) cps[i + 1] else -1
            when {
                (cp in 'a'.code..'z'.code) || (cp in '0'.code..'9'.code) -> {
                    out.append(cp.toChar()); afterNonLatinLetter = false
                }
                Character.getType(cp) == Character.FORMAT.toInt() -> Unit // ZWJ, sélecteurs de variante...
                cp in APOSTROPHES -> Unit
                cp == '&'.code -> { out.append(" and "); afterNonLatinLetter = false }
                cp == '+'.code -> {
                    out.append(if (prev != '+'.code && next != '+'.code) " and " else " ")
                    afterNonLatinLetter = false
                }
                cp == '$'.code -> {
                    out.append(if (isWord(prev) || isWord(next)) "s" else " "); afterNonLatinLetter = false
                }
                cp == '!'.code -> {
                    out.append(if (isLetter(prev) && isLetter(next)) "i" else " "); afterNonLatinLetter = false
                }
                Character.isLetterOrDigit(cp) -> {
                    if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) {
                        appendLatin(out, cp); afterNonLatinLetter = false
                    } else {
                        out.appendCodePoint(if (cp == 'ё'.code) 'е'.code else cp); afterNonLatinLetter = true
                    }
                }
                isMark(cp) -> if (afterNonLatinLetter) out.appendCodePoint(cp)
                else -> { out.append(' '); afterNonLatinLetter = false }
            }
        }
        return out.toString().split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { if (it == "et" || it == "und") "and" else it }
    }

    /** Jetons de [normalize]. */
    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Forme compacte (sans espaces) de [normalize] : `AC/DC` = `ACDC`, `R.E.M.` = `REM`. */
    fun compact(text: String): String = normalize(text).replace(" ", "")

    private val APOSTROPHES = setOf('\''.code, '’'.code, '‘'.code, 'ʼ'.code, '`'.code, '´'.code)

    private fun isWord(cp: Int) = cp >= 0 && Character.isLetterOrDigit(cp)
    private fun isLetter(cp: Int) = cp >= 0 && Character.isLetter(cp)
    private fun isMark(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() ||
            t == Character.ENCLOSING_MARK.toInt()
    }

    private fun appendLatin(out: StringBuilder, cp: Int) {
        when (cp) {
            'ø'.code -> out.append('o')
            'đ'.code, 'ð'.code -> out.append('d')
            'ł'.code -> out.append('l')
            'ħ'.code -> out.append('h')
            'ı'.code -> out.append('i')
            'ß'.code -> out.append("ss")
            'æ'.code -> out.append("ae")
            'œ'.code -> out.append("oe")
            'þ'.code -> out.append("th")
            else -> {
                val d = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
                var j = 0
                while (j < d.length) {
                    val c = d.codePointAt(j)
                    if (!isMark(c)) out.appendCodePoint(c)
                    j += Character.charCount(c)
                }
            }
        }
    }

    /**
     * Normalise un nom d'artiste / de chaîne : [normalize] puis retrait des suffixes de chaîne
     * (`VEVO`, `Official`, `Topic`, `Channel`), y compris collés (`TheWeekndVEVO` → `theweeknd`).
     */
    fun normalizeArtist(name: String): String {
        val n = normalize(name)
        if (n.isEmpty()) return ""
        val cleaned = n.split(' ').mapNotNull { tok ->
            when {
                tok in CHANNEL_NOISE -> null
                tok.length > 4 && tok.endsWith("vevo") -> tok.removeSuffix("vevo")
                tok.length > 8 && tok.endsWith("official") -> tok.removeSuffix("official")
                else -> tok
            }
        }.joinToString(" ")
        return cleaned.ifBlank { n }
    }

    private val CHANNEL_NOISE = setOf("vevo", "official", "officiel", "oficial", "topic", "channel")

    // ---------------------------------------------------------------------------------------------
    // Décorations
    // ---------------------------------------------------------------------------------------------

    private val BRACKET_GROUP = Regex("""[(\[{（【［〔]([^)\]}）】］〕]*)[)\]}）】］〕]""")
    private val DASH_SEGMENT_SEP = Regex("""\s+[-–—―|]\s+""")
    private val WHITESPACE = Regex("""\s+""")

    private val OFFICIAL_TOKENS = setOf("official", "officiel", "officielle", "oficial", "ufficiale", "offiziell", "offizielles")

    /** Jetons « forts » : à eux seuls ils prouvent une décoration. */
    private val STRONG_NOISE = setOf(
        "official", "officiel", "officielle", "oficial", "ufficiale", "offiziell", "offizielles",
        "video", "videoclip", "vid", "audio", "lyric", "lyrics", "lyrical", "visualizer", "visualiser", "clip",
        "mv", "hd", "hq", "4k", "8k", "1080p", "720p", "60fps", "explicit", "clean",
        "remaster", "remastered", "remasters", "remasterise", "remasterisee", "remasterizado", "remasterizada",
        "version", "edition", "deluxe", "letra", "paroles", "testo", "topic", "vevo",
    )

    /** Jetons « faibles » : bruit seulement en compagnie d'un jeton fort (`(Original Mix)`, `(Album Version)`). */
    private val WEAK_NOISE = setOf(
        "music", "musique", "musica", "digital", "expanded", "bonus", "track", "original", "mix", "single", "album",
        "mono", "stereo", "with", "w", "sub", "espanol", "subtitulado", "subtitulada", "traduction", "traduccion",
        "subtitles", "subs", "full", "song", "only", "censored", "uncensored", "de", "la", "le",
    )

    private val YEAR = Regex("""(19|20)\d{2}""")

    /** Vrai si [tok] (déjà normalisé) est du bruit ; les années ne comptent que dans un contexte « remaster ». */
    private fun isNoiseToken(tok: String, remasterContext: Boolean): Boolean =
        tok.isEmpty() || tok in STRONG_NOISE || tok in WEAK_NOISE || (remasterContext && YEAR.matches(tok))

    private fun tokenKey(raw: String): String = compact(raw)

    /** Vrai si le groupe est entièrement du bruit (au moins un jeton fort, ou une simple année). */
    private fun isAllNoise(rawTokens: List<String>): Boolean {
        val keys = rawTokens.map(::tokenKey)
        val meaningful = keys.filter { it.isNotEmpty() }
        if (meaningful.isEmpty()) return true
        val remaster = meaningful.any { it.startsWith("remaster") }
        if (meaningful.size == 1 && YEAR.matches(meaningful[0])) return true
        return meaningful.all { isNoiseToken(it, remaster) } && meaningful.any { it in STRONG_NOISE || it.startsWith("remaster") }
    }

    private fun cleanGroup(m: MatchResult): String {
        val content = m.groupValues[1]
        val toks = content.split(WHITESPACE).filter { it.isNotBlank() }
        if (toks.isEmpty()) return ""
        if (isAllNoise(toks)) return ""
        val keys = toks.map(::tokenKey)
        val official = keys.any { it in OFFICIAL_TOKENS }
        val remaster = keys.any { it.startsWith("remaster") }
        if (!official && !remaster) return m.value
        val kept = toks.filterIndexed { i, _ -> !isNoiseToken(keys[i], remaster) && !keys[i].startsWith("remaster") }
        if (kept.isEmpty()) return ""
        return "${m.value.first()}${kept.joinToString(" ")}${m.value.last()}"
    }

    private val TRAILING_NOISE = Regex(
        """[\s,]+(?:official\s+(?:music\s+|lyrics?\s+|hd\s+)?(?:video|audio|visuali[sz]er|clip|mv)(?:\s+clip)?|""" +
            """(?:lyrics?|music|hd)\s+video|(?:with\s+)?lyrics?|video\s+clip|visuali[sz]er|full\s+hd|hd|hq|4k|8k|1080p|720p)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Retire le bruit de titre : groupes entre `()`, `[]`, `{}`, `【】` qui ne contiennent que de la décoration
     * (`Official Video`, `Lyrics`, `Audio`, `Visualizer`, `Clip officiel`, `Explicit`, `2011 Remaster`...),
     * segments finaux après ` - ` / ` | ` (`- Remastered 2011`, `| Official Video`), suffixes non entourés
     * (`HD`, `4K`, `Official Video`).
     *
     * Les **marqueurs de version** qui changent l'identité du morceau sont conservés : live, acoustic, remix,
     * instrumental, cover, karaoke, sped up, slowed, nightcore, extended, radio edit, demo... Les groupes `(feat. X)`
     * sont aussi conservés (voir [extractFeaturing]). Un groupe mixte comme `(Official Live Video)` devient `(Live)`.
     */
    fun stripDecorations(title: String): String {
        var s = BRACKET_GROUP.replace(title) { cleanGroup(it) }
        s = stripDashSegments(s)
        var prev: String
        do {
            prev = s
            s = TRAILING_NOISE.replace(s, "")
        } while (s != prev)
        s = tidy(s)
        return s.ifBlank { title.trim() }
    }

    private fun stripDashSegments(s: String): String {
        val seps = DASH_SEGMENT_SEP.findAll(s).map { it.value }.toList()
        if (seps.isEmpty()) return s
        val parts = s.split(DASH_SEGMENT_SEP)
        val sb = StringBuilder(parts[0])
        for (i in 1 until parts.size) {
            val toks = parts[i].split(WHITESPACE).filter { it.isNotBlank() }
            if (toks.isNotEmpty() && !isAllNoise(toks)) sb.append(seps[i - 1]).append(parts[i])
        }
        return sb.toString()
    }

    private val EMPTY_BRACKETS = Regex("""[(\[{（【［〔]\s*[)\]}）】］〕]""")
    private val DANGLING = Regex("""^[\s\-–—―|,:]+|[\s\-–—―|,:]+$""")

    private fun tidy(s: String): String =
        DANGLING.replace(WHITESPACE.replace(EMPTY_BRACKETS.replace(s, " "), " "), "").trim()

    // ---------------------------------------------------------------------------------------------
    // Featuring
    // ---------------------------------------------------------------------------------------------

    private const val FEAT_WORD = """(?:featuring|feat|ft)"""
    private val BRACKET_FEAT = Regex(
        """\s*[(\[{（【［]\s*(?:$FEAT_WORD(?![\p{L}\p{N}])\.?|with(?![\p{L}\p{N}]))\s*([^)\]}）】］]+?)\s*[)\]}）】］]""",
        RegexOption.IGNORE_CASE,
    )
    private val INLINE_FEAT = Regex(
        """\s*(?<![\p{L}\p{N}])$FEAT_WORD(?![\p{L}\p{N}])\.?\s+(.+?)(?=\s+[-–—|]\s+|\s*[(\[{（【［]|$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Extrait les artistes invités d'un titre : `Song (feat. A & B)`, `Song [ft. A]`, `Song (with A)` (avec
     * `with` uniquement entre parenthèses), `Song feat. A`, `Song featuring A`.
     * Renvoie le titre sans ces parties (le reste, ex. `[Remix]`, est conservé) et la liste des invités.
     */
    fun extractFeaturing(title: String): Featuring {
        val featured = mutableListOf<String>()
        var s = BRACKET_FEAT.replace(title) { featured += splitPlain(it.groupValues[1]); " " }
        s = INLINE_FEAT.replace(s) { featured += splitPlain(it.groupValues[1]); " " }
        val cleaned = tidy(s).ifBlank { title.trim() }
        return Featuring(cleaned, featured.distinctBy(::compact).filter { it.isNotBlank() })
    }

    // ---------------------------------------------------------------------------------------------
    // Artistes
    // ---------------------------------------------------------------------------------------------

    private val ARTIST_SEP = Regex(
        """\s*[,;]\s*|\s+&\s+|\s+(?i:and|et)\s+|\s+\+\s+|\s+x\s+|\s*×\s*|\s+(?i:vs)\.?\s+""",
    )

    private fun splitPlain(s: String): List<String> =
        s.split(ARTIST_SEP).map { it.trim().trim('.', ' ') }.filter { it.isNotBlank() }

    /**
     * Découpe une chaîne d'artistes : `"A, B & C feat. D"` → `[A, B, C, D]` (casse d'origine conservée,
     * doublons retirés). Sépare sur `,` `;` `&` `and` `et` `+` ` x ` `vs` et `feat./ft./featuring` ; jamais sur `/`
     * (`AC/DC`). Limite connue : `Tyler, The Creator` est séparé — comparer aussi la chaîne entière.
     */
    fun splitArtists(text: String): List<String> {
        val f = extractFeaturing(text)
        return (splitPlain(f.title) + f.featured).distinctBy(::compact).filter { it.isNotBlank() }
    }

    // ---------------------------------------------------------------------------------------------
    // Marqueurs de version
    // ---------------------------------------------------------------------------------------------

    private val VERSION_PATTERNS: List<Pair<String, Regex>> = listOf(
        "live" to """\blive\b""",
        "acoustic" to """\b(?:acoustic|unplugged)\b""",
        "remix" to """\b(?:re ?mix(?:ed|es)?|rmx)\b""",
        "instrumental" to """\binstrumentals?\b""",
        "cover" to """\b(?:covers?|covered)\b""",
        "karaoke" to """\b(?:karaoke|originally performed by|in the style of|backing track|sing along)\b""",
        "sped up" to """\b(?:sped up|speed(?:ed)? up|speedup|spedup)\b""",
        "slowed" to """\b(?:slowed|slow(?: and)? reverb)\b""",
        "nightcore" to """\bnightcore\b""",
        "extended" to """\bextended\b""",
        "radio edit" to """\b(?:radio (?:edit|version|mix)|radio cut)\b""",
        "demo" to """\bdemo\b""",
        "reprise" to """\breprise\b""",
        "acapella" to """\ba ?cap+ella\b""",
        "8d" to """\b8d\b""",
        "mashup" to """\bmash ?up\b""",
        "lofi" to """\blo ?fi\b""",
        "bass boosted" to """\bbass boosted\b""",
    ).map { (tag, re) -> tag to Regex(re) }

    /**
     * Marqueurs de version présents dans [text] : live, acoustic, remix, instrumental, cover, karaoke, sped up,
     * slowed, nightcore, extended, radio edit, demo, reprise, acapella, 8d (+ mashup, lofi, bass boosted).
     */
    fun versionTags(text: String): Set<String> {
        val n = normalize(text)
        if (n.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        for ((tag, re) in VERSION_PATTERNS) if (re.containsMatchIn(n)) result += tag
        return result
    }
}
