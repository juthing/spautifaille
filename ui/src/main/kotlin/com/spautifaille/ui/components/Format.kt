package com.spautifaille.ui.components

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Nombre compact (« 1,2 k », « 3,4 M ») pour les compteurs d'abonnés. */
fun formatCompactCount(count: Long): String = when {
    count < 1_000L -> count.toString()
    count < 1_000_000L -> compact(count / 1_000.0, "k")
    count < 1_000_000_000L -> compact(count / 1_000_000.0, "M")
    else -> compact(count / 1_000_000_000.0, "Md")
}

private fun compact(value: Double, suffix: String): String {
    val pattern = if (value >= 100) "%.0f" else "%.1f"
    val text = String.format(Locale.getDefault(), pattern, value)
    val trimmed = if (text.endsWith(",0") || text.endsWith(".0")) text.dropLast(2) else text
    return "$trimmed $suffix"
}

/** Formate une durée en `m:ss` (ou `h:mm:ss` au-delà d'une heure). Les valeurs négatives valent 0. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** Date longue localisée (« 12 mars 2021 » en français). */
fun formatUploadDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(date)
