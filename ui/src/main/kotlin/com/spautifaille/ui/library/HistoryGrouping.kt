package com.spautifaille.ui.library

import com.spautifaille.domain.model.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Jour d'un groupe d'historique, relatif à « aujourd'hui ». */
sealed interface HistoryDay {
    data object Today : HistoryDay
    data object Yesterday : HistoryDay
    data class On(val date: LocalDate) : HistoryDay
}

/** Élément aplati de la liste d'historique : un en-tête de jour ou une entrée. */
sealed interface HistoryListItem {
    data class Header(val day: HistoryDay, val date: LocalDate) : HistoryListItem

    /** [index] est l'index de l'entrée dans la liste d'historique d'origine (ordre récent → ancien). */
    data class Entry(val index: Int, val entry: HistoryEntry) : HistoryListItem
}

/**
 * Regroupe l'historique (déjà trié du plus récent au plus ancien) par jour calendaire local.
 * Les groupes gardent l'ordre d'apparition ; l'index d'origine est conservé pour la lecture « à partir d'ici ».
 */
fun groupHistoryByDay(
    history: List<HistoryEntry>,
    today: LocalDate,
    zone: ZoneId,
): List<HistoryListItem> {
    val result = ArrayList<HistoryListItem>(history.size + 4)
    var currentDate: LocalDate? = null
    history.forEachIndexed { index, entry ->
        val date = Instant.ofEpochMilli(entry.playedAt).atZone(zone).toLocalDate()
        if (date != currentDate) {
            currentDate = date
            val day = when (date) {
                today -> HistoryDay.Today
                today.minusDays(1) -> HistoryDay.Yesterday
                else -> HistoryDay.On(date)
            }
            result += HistoryListItem.Header(day, date)
        }
        result += HistoryListItem.Entry(index, entry)
    }
    return result
}
