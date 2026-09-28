package com.spautifaille.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R

/** Largeur maximale du contenu des listes sur grands écrans. */
internal val LibraryContentMaxWidth: Dp = 840.dp

/** Pochette carrée avec repli (note de musique) tant que l'image charge ou si elle est absente. */
@Composable
internal fun LibraryArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    fallbackIcon: ImageVector = Icons.Filled.MusicNote,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.5f),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Pochette de la playlist système « Titres likés » : un cœur sur fond `primaryContainer`. */
@Composable
internal fun LibraryLikedArtwork(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxSize(0.5f),
        )
    }
}

/** Ligne de titre : pochette, titre, « artiste · durée ». */
@Composable
internal fun LibraryTrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val duration = track.durationMs?.let(::formatDuration)
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = if (duration != null) "${track.artist} · $duration" else track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { LibraryArtwork(track.thumbnailUrl, Modifier.size(48.dp)) },
        trailingContent = trailing,
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
    )
}

/** État vide : icône, titre, texte d'aide et action optionnelle. */
@Composable
internal fun LibraryEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

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
                label = { Text(stringResource(R.string.lib_playlist_name_label)) },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

@Composable
internal fun trackCountText(count: Int): String =
    pluralStringResource(R.plurals.lib_track_count, count, count)

@Composable
internal fun storageText(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

/** `m:ss` ou `h:mm:ss`. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

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

/** 1 234 → « 1,2 k », 1 500 000 → « 1,5 M ». */
internal fun formatCompactCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1f M".format(count / 1_000_000.0)
    count >= 10_000 -> "%.0f k".format(count / 1_000.0)
    count >= 1_000 -> "%.1f k".format(count / 1_000.0)
    else -> count.toString()
}
