package com.spautifaille.ui.youtube

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.spautifaille.ui.R
import java.util.concurrent.TimeUnit

/** Ancienneté d'une synchronisation, en unités lisibles (« à l'instant », « il y a 5 min », « il y a 3 h », « il y a 2 j »). */
sealed interface SyncAge {
    data object JustNow : SyncAge
    data class Minutes(val value: Int) : SyncAge
    data class Hours(val value: Int) : SyncAge
    data class Days(val value: Int) : SyncAge
}

/** Ancienneté de [lastSyncAt] à l'instant [now] (une date future est ramenée à « à l'instant »). Fonction pure. */
fun syncAge(lastSyncAt: Long, now: Long): SyncAge {
    val elapsed = (now - lastSyncAt).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    return when {
        minutes < 1 -> SyncAge.JustNow
        minutes < 60 -> SyncAge.Minutes(minutes.toInt())
        minutes < 24 * 60 -> SyncAge.Hours(TimeUnit.MINUTES.toHours(minutes).toInt())
        else -> SyncAge.Days(TimeUnit.MINUTES.toDays(minutes).toInt())
    }
}

/** « Synchronisé à l'instant » / « Synchronisé il y a 5 min »… */
@Composable
fun SyncAge.label(): String = when (this) {
    SyncAge.JustNow -> stringResource(R.string.yt_synced_just_now)
    is SyncAge.Minutes -> stringResource(R.string.yt_synced_minutes_ago, value)
    is SyncAge.Hours -> stringResource(R.string.yt_synced_hours_ago, value)
    is SyncAge.Days -> stringResource(R.string.yt_synced_days_ago, value)
}
