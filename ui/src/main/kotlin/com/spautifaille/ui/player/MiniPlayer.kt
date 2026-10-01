package com.spautifaille.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlin.math.abs

/** Hauteur réservée au mini lecteur (carte + marges). */
val MiniPlayerHeight = 72.dp

private const val SWIPE_UP_VELOCITY = -400f
private val MiniSwipeThreshold = 72.dp
private val MiniSwipeFlingVelocity = 700.dp
private const val FOLLOW_FACTOR = 0.6f
private const val RUBBER_BAND = 0.25f

/**
 * Mini lecteur : carte tonale arrondie, légèrement décollée des bords, avec pochette, titre / artiste,
 * lecture-pause, suivant et fine barre de progression arrondie. Toucher ou glisser vers le haut ouvre le lecteur
 * plein écran ; glisser horizontalement change de titre (suivant vers la gauche, précédent vers la droite), avec
 * un cran haptique au franchissement du seuil. [progress] est lue à la phase de dessin.
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
    onPrevious: () -> Unit = {},
    transition: PlayerTransition? = null,
) {
    val haptics = LocalAppHaptics.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { MiniSwipeThreshold.toPx() }
    val flingPx = with(density) { MiniSwipeFlingVelocity.toPx() }
    val canNext by rememberUpdatedState(hasNext)
    val swipeNext by rememberUpdatedState(onNext)
    val swipePrevious by rememberUpdatedState(onPrevious)
    var offsetX by remember { mutableFloatStateOf(0f) }
    var pastThreshold by remember { mutableStateOf(false) }

    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .graphicsLayer {
                translationX = offsetX * FOLLOW_FACTOR
                alpha = 1f - (abs(offsetX) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 0.5f)
            }
            .playerSharedBounds(PlayerSharedKeys.Container, transition, shape)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val blocked = delta < 0f && offsetX <= 0f && !canNext
                    offsetX += if (blocked) delta * RUBBER_BAND else delta
                    val past = abs(offsetX) >= thresholdPx && !(offsetX < 0f && !canNext)
                    if (past != pastThreshold) {
                        pastThreshold = past
                        if (past) haptics.gestureThreshold()
                    }
                },
                onDragStopped = { velocity ->
                    pastThreshold = false
                    when (resolveHorizontalSwipe(offsetX, velocity, thresholdPx, flingPx)) {
                        SwipeResult.Next -> if (canNext) swipeNext()
                        SwipeResult.Previous -> swipePrevious()
                        SwipeResult.None -> Unit
                    }
                    animate(offsetX, 0f, animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                        offsetX = v
                    }
                },
            )
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { },
                onDragStopped = { velocity ->
                    if (velocity < SWIPE_UP_VELOCITY) {
                        haptics.click()
                        onExpand()
                    }
                },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        onClick = {
            haptics.click()
            onExpand()
        },
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    url = track.thumbnailUrl,
                    modifier = Modifier
                        .size(44.dp)
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
                        fontWeight = FontWeight.Bold,
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
                    FilledIconButton(
                        onClick = {
                            haptics.click()
                            onPlayPause()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (isPlaying) R.string.common_action_pause else R.string.common_action_play),
                        )
                    }
                    if (isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(46.dp), strokeWidth = 2.dp)
                    }
                }
                IconButton(
                    onClick = {
                        haptics.click()
                        onNext()
                    },
                    enabled = hasNext,
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.common_action_next))
                }
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                    .height(3.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
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

@Preview(showBackground = true)
@Composable
private fun MiniPlayerPausedPreview() {
    SpautifailleTheme(dynamicColor = false, themeMode = com.spautifaille.domain.model.ThemeMode.DARK) {
        MiniPlayer(
            track = Track(id = "a", title = "Titre court", artist = "Artiste"),
            isPlaying = false,
            isBuffering = false,
            hasNext = false,
            progress = { 0.75f },
            onExpand = {},
            onPlayPause = {},
            onNext = {},
        )
    }
}
