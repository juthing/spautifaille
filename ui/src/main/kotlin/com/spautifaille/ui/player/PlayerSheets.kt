package com.spautifaille.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.ui.R
import com.spautifaille.ui.components.formatDuration
import com.spautifaille.ui.components.hideSheet
import java.text.DecimalFormat

val SpeedPresets: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
val SleepTimerPresetsMinutes: List<Int> = listOf(5, 10, 15, 30, 45, 60)

/** « 1× », « 1,25× » : sans zéros inutiles, selon la locale. */
fun formatSpeed(speed: Float): String = DecimalFormat("0.##").format(speed.toDouble()) + "×"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.player_speed_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SpeedPresets.forEach { preset ->
                val selected = kotlin.math.abs(preset - currentSpeed) < 0.001f
                ListItem(
                    modifier = Modifier.clickable {
                        onSelect(preset)
                        scope.hideSheet(sheetState, onDismiss)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(formatSpeed(preset)) },
                    trailingContent = { RadioButton(selected = selected, onClick = null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    timer: SleepTimer,
    onSelectMinutes: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.player_sleep_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            when (timer) {
                SleepTimer.Off -> Unit
                SleepTimer.EndOfTrack -> Text(
                    text = stringResource(R.string.player_sleep_active_end_of_track),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                is SleepTimer.At -> Text(
                    text = stringResource(R.string.player_sleep_active_remaining, formatDuration(timer.remainingMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            SleepTimerPresetsMinutes.forEach { minutes ->
                ListItem(
                    modifier = Modifier.clickable {
                        onSelectMinutes(minutes)
                        scope.hideSheet(sheetState, onDismiss)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Filled.Timer, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.player_sleep_minutes, minutes)) },
                )
            }
            ListItem(
                modifier = Modifier.clickable {
                    onEndOfTrack()
                    scope.hideSheet(sheetState, onDismiss)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.player_sleep_end_of_track)) },
            )
            if (timer != SleepTimer.Off) {
                ListItem(
                    modifier = Modifier.clickable {
                        onCancel()
                        scope.hideSheet(sheetState, onDismiss)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Filled.Close, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.player_sleep_cancel)) },
                )
            }
        }
    }
}
