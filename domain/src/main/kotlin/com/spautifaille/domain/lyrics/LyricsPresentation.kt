package com.spautifaille.domain.lyrics

/** Pause minimale (ms) représentée par les trois points ; en dessous, la ligne vide est simplement masquée. */
const val MIN_BREAK_MS = 4_000L

/**
 * Lignes à afficher : les pauses plus courtes que [minBreakMs] sont masquées (la ligne précédente reste alors la
 * ligne courante), et une introduction d'au moins [minBreakMs] avant la première ligne est matérialisée par une
 * pause à 0.
 */
fun List<LyricLine>.displayLines(minBreakMs: Long = MIN_BREAK_MS): List<LyricLine> {
    if (isEmpty()) return emptyList()
    val out = ArrayList<LyricLine>(size + 1)
    forEachIndexed { i, line ->
        if (line.isBreak) {
            val next = getOrNull(i + 1) ?: return@forEachIndexed
            if (next.timeMs - line.timeMs < minBreakMs) return@forEachIndexed
        }
        out += line
    }
    val first = out.firstOrNull() ?: return out
    if (!first.isBreak && first.timeMs >= minBreakMs) out.add(0, LyricLine(0L, ""))
    return out
}

/**
 * Index de la ligne courante à [positionMs] (dernière ligne dont l'horodatage est atteint), ou -1 avant la
 * première. Les lignes doivent être triées par temps.
 */
fun List<LyricLine>.indexAt(positionMs: Long): Int {
    var low = 0
    var high = lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].timeMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}
