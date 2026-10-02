package com.spautifaille.domain.lyrics

/**
 * Parseur du format LRC.
 *
 * - Horodatages `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]` (`:` accepté comme séparateur de fraction),
 *   plusieurs par ligne (`[00:12.00][01:30.50]Refrain`) ; chaque horodatage produit une ligne.
 * - `[offset:+500]` (ms) : valeur positive = paroles affichées plus tôt (l'horodatage effectif est `t - offset`).
 * - Métadonnées (`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[length:]`...) ignorées ; balises de mots `<mm:ss.xx>`
 *   (LRC « enhanced ») retirées du texte.
 * - Une ligne horodatée sans texte est conservée comme pause ([LyricLine.isBreak]) ; les pauses consécutives sont
 *   fusionnées et celles de fin de morceau retirées.
 * - Résultat trié par temps (tri stable), horodatages négatifs ramenés à 0.
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val TAG = Regex("""\[([A-Za-z#]+)\s*:([^\]]*)]""")
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(lrc: String): List<LyricLine> {
        var offsetMs = 0L
        val raw = ArrayList<LyricLine>()
        for (line in lrc.lineSequence()) {
            var pos = 0
            val times = ArrayList<Long>(2)
            val trimmed = line.trimStart('﻿', ' ', '\t')
            while (pos < trimmed.length && trimmed[pos] == '[') {
                val ts = TIMESTAMP.matchAt(trimmed, pos)
                if (ts != null) {
                    times += toMillis(ts)
                    pos = ts.range.last + 1
                    continue
                }
                val tag = TAG.matchAt(trimmed, pos) ?: break
                if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                    tag.groupValues[2].trim().removePrefix("+").toLongOrNull()?.let { offsetMs = it }
                }
                pos = tag.range.last + 1
            }
            if (times.isEmpty()) continue
            val text = WORD_TAG.replace(trimmed.substring(pos), "").trim()
            times.forEach { raw += LyricLine(it, text) }
        }
        val shifted = raw.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
        return tidy(shifted.sortedBy { it.timeMs })
    }

    private fun tidy(sorted: List<LyricLine>): List<LyricLine> {
        val out = ArrayList<LyricLine>(sorted.size)
        for (line in sorted) {
            if (line.isBreak && out.lastOrNull()?.isBreak == true) continue
            out += line
        }
        while (out.lastOrNull()?.isBreak == true) out.removeAt(out.lastIndex)
        return out
    }

    private fun toMillis(m: MatchResult): Long {
        val minutes = m.groupValues[1].toLong()
        val seconds = m.groupValues[2].toLong()
        val fraction = m.groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return (minutes * 60 + seconds) * 1000 + fractionMs
    }
}
