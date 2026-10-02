package com.spautifaille.domain.lyrics

/** Une ligne de paroles synchronisée. Un [text] vide représente une pause (passage instrumental). */
data class LyricLine(val timeMs: Long, val text: String) {
    val isBreak: Boolean get() = text.isBlank()
}

/** Paroles d'un titre. [source] est le nom du fournisseur, affiché discrètement dans l'UI. */
sealed interface Lyrics {
    val source: String

    /** Paroles horodatées, triées par [LyricLine.timeMs]. */
    data class Synced(val lines: List<LyricLine>, override val source: String = SOURCE_LRCLIB) : Lyrics

    /** Paroles sans horodatage : affichées telles quelles, sans suivi de la lecture. */
    data class Plain(val text: String, override val source: String = SOURCE_LRCLIB) : Lyrics

    /** Le fournisseur indique que le morceau est instrumental. */
    data class Instrumental(override val source: String = SOURCE_LRCLIB) : Lyrics

    companion object {
        const val SOURCE_LRCLIB = "LRCLIB"
    }
}
