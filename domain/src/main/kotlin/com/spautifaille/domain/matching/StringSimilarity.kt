package com.spautifaille.domain.matching

import kotlin.math.max
import kotlin.math.min

/**
 * Mesures de similarité entre chaînes, toutes dans [0, 1]. Les entrées sont supposées déjà normalisées
 * (voir [TextNormalizer.normalize]) ; les fonctions travaillent sur des points de code Unicode.
 *
 * Cas limites : deux chaînes égales (y compris vides) → 1.0 ; une seule vide → 0.0.
 */
object StringSimilarity {

    /** Similarité de Jaro. */
    fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        val window = max(max(x.size, y.size) / 2 - 1, 0)
        val xMatched = BooleanArray(x.size)
        val yMatched = BooleanArray(y.size)
        var matches = 0
        for (i in x.indices) {
            val from = max(0, i - window)
            val to = min(y.size - 1, i + window)
            for (j in from..to) {
                if (!yMatched[j] && x[i] == y[j]) {
                    xMatched[i] = true
                    yMatched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in x.indices) {
            if (!xMatched[i]) continue
            while (!yMatched[k]) k++
            if (x[i] != y[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        return (m / x.size + m / y.size + (m - transpositions / 2.0) / m) / 3.0
    }

    /** Jaro-Winkler : Jaro + bonus de préfixe commun (≤ 4 caractères) quand Jaro > 0.7. */
    fun jaroWinkler(a: String, b: String, prefixScale: Double = 0.1): Double {
        val j = jaro(a, b)
        if (j <= 0.7) return j
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var prefix = 0
        while (prefix < min(4, min(x.size, y.size)) && x[prefix] == y[prefix]) prefix++
        return min(1.0, j + prefix * prefixScale * (1.0 - j))
    }

    /** Ratio « Indel » : 2·LCS / (|a| + |b|). Équivalent du `ratio` de fuzzywuzzy. */
    fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var prev = IntArray(y.size + 1)
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            for (j in 1..y.size) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else max(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        return 2.0 * prev[y.size] / (x.size + y.size)
    }

    /**
     * Équivalent de `token_set_ratio` de fuzzywuzzy : insensible à l'ordre et aux doublons de mots.
     * Avec `sect` = mots communs, `rA`/`rB` = mots propres à chaque côté :
     * max(ratio(sect, sect+rA), ratio(sect, sect+rB), ratio(sect+rA, sect+rB)).
     * Particularité héritée : si les mots d'un côté sont inclus dans l'autre, le résultat est 1.0
     * (`"hello"` vs `"hello world"`) — les appelants doivent compenser (voir [TrackMatcher]).
     */
    fun tokenSetRatio(a: String, b: String): Double {
        val ta = a.split(' ', '\t', '\n').filter { it.isNotEmpty() }.toSortedSet()
        val tb = b.split(' ', '\t', '\n').filter { it.isNotEmpty() }.toSortedSet()
        if (ta.isEmpty() && tb.isEmpty()) return 1.0
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val common = ta.filter { it in tb }
        val onlyA = ta.filter { it !in tb }
        val onlyB = tb.filter { it !in ta }
        val sect = common.joinToString(" ")
        val combA = (common + onlyA).joinToString(" ")
        val combB = (common + onlyB).joinToString(" ")
        var best = ratio(combA, combB)
        if (sect.isNotEmpty()) best = max(best, max(ratio(sect, combA), ratio(sect, combB)))
        return best
    }
}
