package com.spautifaille.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.spautifaille.ui.R

/** Dialogue de saisie d'un nom de playlist avec validation (non vide après `trim()`, 100 caractères max). */
@Composable
internal fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val error = PlaylistNameValidator.validate(name)
    val showError = error != null && (touched || name.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    touched = true
                },
                label = { Text(stringResource(R.string.common_playlist_name_label)) },
                singleLine = true,
                isError = showError,
                supportingText = {
                    when {
                        showError && error == PlaylistNameError.BLANK ->
                            Text(stringResource(R.string.lib_playlist_name_blank))
                        showError && error == PlaylistNameError.TOO_LONG ->
                            Text(stringResource(R.string.lib_playlist_name_too_long, PlaylistNameValidator.MAX_LENGTH))
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(PlaylistNameValidator.normalize(name)) }, enabled = error == null) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

/** Dialogue de confirmation d'une action destructrice. */
@Composable
internal fun LibraryConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

@Composable
internal fun trackCountText(count: Int): String =
    pluralStringResource(R.plurals.lib_track_count, count, count)

/** Durée totale lisible : « 1 h 23 min » ou « 42 min ». */
@Composable
internal fun totalDurationText(ms: Long): String {
    val totalMinutes = (ms / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        stringResource(R.string.lib_duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.lib_duration_minutes, minutes.coerceAtLeast(1))
    }
}
