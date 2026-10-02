package com.spautifaille.domain.model

import java.time.LocalDate

/** Statistiques publiques d'un titre ; chaque valeur est nulle quand elle est inconnue. */
data class TrackStats(
    val viewCount: Long? = null,
    val likeCount: Long? = null,
    val uploadDate: LocalDate? = null,
) {
    val isEmpty: Boolean get() = viewCount == null && likeCount == null && uploadDate == null
}
