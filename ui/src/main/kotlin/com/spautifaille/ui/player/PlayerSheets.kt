package com.spautifaille.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.formatDuration
import com.spautifaille.ui.components.hideSheet
import com.spautifaille.ui.theme.Spacing
import java.text.DecimalFormat

val SpeedPresets: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
val SleepTimerPresetsMinutes: List<Int> = listOf(5, 10, 15, 30, 45, 60)

/** « 1× », « 1,25× » : sans zéros inutiles, selon la locale. */
fun formatSpeed(speed: Float): String = DecimalFormat("0.##").format(speed.toDouble()) + "×"

/** Choix de la vitesse de lecture : valeur courante en grand, préréglages en puces sélectionnables. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.m)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.player_speed_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = formatSpeed(currentSpeed),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                SpeedPresets.forEach { preset ->
                    val selected = kotlin.math.abs(preset - currentSpeed) < 0.001f
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptics.tick()
                            onSelect(preset)
                            scope.hideSheet(sheetState, onDismiss)
                        },
                        label = { Text(formatSpeed(preset)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** Minuterie de sommeil : état actif en haut (avec annulation), puis durées et « fin du titre » en puces. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    timer: SleepTimer,
    remainingProvider: () -> Long?,
    onSelectMinutes: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.m)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                text = stringResource(R.string.player_sleep_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (timer != SleepTimer.Off) {
                SleepStatusCard(
                    timer = timer,
                    remainingProvider = remainingProvider,
                    onCancel = {
                        haptics.confirm()
                        onCancel()
                        scope.hideSheet(sheetState, onDismiss)
                    },
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                SleepTimerPresetsMinutes.forEach { minutes ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            haptics.tick()
                            onSelectMinutes(minutes)
                            scope.hideSheet(sheetState, onDismiss)
                        },
                        label = { Text(stringResource(R.string.player_sleep_chip_minutes, minutes)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                        },
                    )
                }
                FilterChip(
                    selected = timer == SleepTimer.EndOfTrack,
                    onClick = {
                        haptics.tick()
                        onEndOfTrack()
                        scope.hideSheet(sheetState, onDismiss)
                    },
                    label = { Text(stringResource(R.string.player_sleep_end_of_track)) },
                    leadingIcon = {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
        }
    }
}

/** Isolée pour que seule cette carte se recompose chaque seconde (le ViewModel recalcule le temps restant). */
@Composable
private fun SleepStatusCard(timer: SleepTimer, remainingProvider: () -> Long?, onCancel: () -> Unit) {
    val text = when (timer) {
        SleepTimer.Off -> ""
        SleepTimer.EndOfTrack -> stringResource(R.string.player_sleep_active_end_of_track)
        is SleepTimer.At -> stringResource(
            R.string.player_sleep_active_remaining,
            // `remainingMs` n'est qu'un repli : il n'est publié qu'aux changements d'état.
            formatDuration(remainingProvider() ?: timer.remainingMs),
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.m, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Bedtime, contentDescription = null)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.s),
            )
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) { Text(stringResource(R.string.player_sleep_cancel)) }
        }
    }
}
