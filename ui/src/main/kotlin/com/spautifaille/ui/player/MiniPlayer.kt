package com.spautifaille.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlin.math.abs

/** Hauteur réservée au mini lecteur : carte de 60 dp + 4 dp de marge au-dessus et au-dessous. */
val MiniPlayerHeight = 68.dp

private const val SWIPE_UP_VELOCITY = -400f
private val MiniSwipeThreshold = 72.dp
private val MiniSwipeFlingVelocity = 700.dp
private const val FOLLOW_FACTOR = 0.6f
private const val RUBBER_BAND = 0.25f
private const val MINI_CROSSFADE_MS = 300

private val MiniCardHeight = 60.dp
private val MiniCardCorner = 20.dp
private val MiniInnerPadding = 8.dp
private val MiniArtworkSize = MiniCardHeight - MiniInnerPadding * 2
private val MiniArtworkCorner = MiniCardCorner - MiniInnerPadding
private val MiniPlayButtonSize = 44.dp
private val MiniProgressHeight = 3.dp

/**
 * Mini lecteur : carte tonale aux coins arrondis (20 dp), pochette aux coins concentriques (20 - 8 = 12 dp)
 * contenue dans la marge intérieure, titre / artiste centrés verticalement (titre en défilement s'il est long),
 * lecture-pause (même forme qui se transforme que le grand bouton) et suivant. La progression est un filet de
 * 3 dp collé au bord inférieur, à l'intérieur de la forme. Toucher ou glisser vers le haut ouvre le lecteur
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

    val shape = RoundedCornerShape(MiniCardCorner)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m - Spacing.xs, vertical = Spacing.xs)
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
        shadowElevation = 2.dp,
        onClick = {
            haptics.click()
            onExpand()
        },
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MiniCardHeight)
                    .padding(horizontal = MiniInnerPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Pochette : coins concentriques avec la carte (rayon carte - marge), jamais rognée par la forme.
                Box(
                    modifier = Modifier
                        .size(MiniArtworkSize)
                        .playerSharedElement(PlayerSharedKeys.Artwork, transition),
                ) {
                    Crossfade(
                        targetState = track.id to track.thumbnailUrl,
                        animationSpec = tween(MINI_CROSSFADE_MS),
                        label = "miniArtwork",
                    ) { (_, url) ->
                        Artwork(
                            url = url,
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(MiniArtworkCorner),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.m),
                    verticalArrangement = Arrangement.Center,
                ) {
                    CrossfadeText(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        marquee = true,
                        modifier = Modifier.playerSharedBounds(PlayerSharedKeys.Title, transition),
                    )
                    CrossfadeText(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PlayPauseButton(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onClick = {
                        haptics.click()
                        onPlayPause()
                    },
                    size = MiniPlayButtonSize,
                    iconSize = 24.dp,
                )
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
            // Progression : filet de 3 dp collé au bord inférieur, à l'intérieur de la forme (la carte la découpe
            // aux coins). Ni espace ni indicateur d'arrêt ; lue à la phase de dessin, sans recomposition.
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(MiniProgressHeight),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Butt,
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
