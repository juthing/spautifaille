package com.spautifaille.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.SpautifailleTheme

/** Hauteur réservée au mini lecteur (carte + marges). */
val MiniPlayerHeight = 72.dp

private const val SWIPE_UP_VELOCITY = -400f

/**
 * Mini lecteur : pochette, titre / artiste, lecture-pause, suivant et fine barre de progression.
 * Toucher ou glisser vers le haut ouvre le lecteur plein écran. [progress] est lue à la phase de dessin.
 */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasNext: Boolean,
    progress: () -> Float,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    transition: PlayerTransition? = null,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .playerSharedBounds(PlayerSharedKeys.Container, transition, shape)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { },
                onDragStopped = { velocity -> if (velocity < SWIPE_UP_VELOCITY) onExpand() },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        onClick = onExpand,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .padding(start = 7.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    url = track.thumbnailUrl,
                    modifier = Modifier
                        .size(48.dp)
                        .playerSharedElement(PlayerSharedKeys.Artwork, transition),
                    shape = MaterialTheme.shapes.small,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.playerSharedBounds(PlayerSharedKeys.Title, transition),
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(contentAlignment = Alignment.Center) {
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (isPlaying) R.string.common_action_pause else R.string.common_action_play),
                        )
                    }
                    if (isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 2.dp)
                    }
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.common_action_next))
                }
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MiniPlayerPreview() {
    SpautifailleTheme(dynamicColor = false) {
        MiniPlayer(
            track = Track(id = "a", title = "Un titre assez long pour tester l'ellipse", artist = "Artiste"),
            isPlaying = true,
            isBuffering = false,
            hasNext = true,
            progress = { 0.4f },
            onExpand = {},
            onPlayPause = {},
            onNext = {},
        )
    }
}
