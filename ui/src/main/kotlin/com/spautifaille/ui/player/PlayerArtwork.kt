package com.spautifaille.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.Artwork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private val SwipeThreshold = 72.dp
private val SwipeFlingVelocity = 700.dp
private const val PAUSED_SCALE = 0.86f
private const val RUBBER_BAND = 0.25f
private const val SLIDE_OUT_MS = 130
private const val TRACK_CHANGE_TIMEOUT_MS = 600L

/**
 * Grande pochette du lecteur : coins arrondis, ombre douce, légèrement réduite en pause. On la fait glisser
 * horizontalement pour passer au titre suivant (vers la gauche) ou précédent (vers la droite) : la pochette
 * suit le doigt, un cran haptique signale le franchissement du seuil, puis elle sort, et la nouvelle entre
 * dès que le titre a changé. Sans titre suivant, le glissement vers la gauche oppose une résistance.
 *
 * [onSwipePrevious] / [onSwipeNext] déclenchent l'action et renvoient `true` si le titre va changer (faux pour
 * « précédent » qui ne fait que redémarrer le titre en cours : la pochette revient alors simplement en place).
 */
@Composable
internal fun SwipeableArtwork(
    trackId: String,
    thumbnailUrl: String?,
    title: String,
    isPlaying: Boolean,
    canSkipNext: Boolean,
    onSwipePrevious: () -> Boolean,
    onSwipeNext: () -> Boolean,
    transition: PlayerTransition?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { SwipeThreshold.toPx() }
    val flingPx = with(density) { SwipeFlingVelocity.toPx() }
    val currentTrackId by rememberUpdatedState(trackId)
    val canNext by rememberUpdatedState(canSkipNext)
    val swipePrevious by rememberUpdatedState(onSwipePrevious)
    val swipeNext by rememberUpdatedState(onSwipeNext)

    var offsetX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var crossed by remember { mutableFloatStateOf(0f) } // 0 = en deçà du seuil, 1 = au-delà
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else PAUSED_SCALE,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artworkScale",
    )
    val shape = MaterialTheme.shapes.extraLarge

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val blocked = delta < 0f && offsetX <= 0f && !canNext
                    offsetX += if (blocked) delta * RUBBER_BAND else delta
                    val past = if (abs(offsetX) >= thresholdPx && !(offsetX < 0f && !canNext)) 1f else 0f
                    if (past != crossed) {
                        crossed = past
                        if (past == 1f) haptics.gestureThreshold()
                    }
                },
                onDragStopped = { velocity ->
                    crossed = 0f
                    val result = resolveHorizontalSwipe(offsetX, velocity, thresholdPx, flingPx)
                    val goNext = result == SwipeResult.Next && canNext
                    val goPrevious = result == SwipeResult.Previous
                    if (!goNext && !goPrevious) {
                        animate(offsetX, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> offsetX = v }
                        return@draggable
                    }
                    val direction = if (goNext) -1f else 1f
                    val startId = currentTrackId
                    animate(offsetX, direction * widthPx, animationSpec = tween(SLIDE_OUT_MS)) { v, _ -> offsetX = v }
                    val willChange = if (goNext) swipeNext() else swipePrevious()
                    if (willChange) {
                        // On attend le nouveau titre pour faire entrer sa pochette (et non l'ancienne) depuis le côté opposé.
                        withTimeoutOrNull(TRACK_CHANGE_TIMEOUT_MS) { snapshotFlow { currentTrackId }.first { it != startId } }
                        offsetX = -direction * widthPx * 0.5f
                    }
                    animate(offsetX, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offsetX = v }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Artwork(
            url = thumbnailUrl,
            contentDescription = title,
            shape = shape,
            modifier = Modifier
                .aspectRatio(1f)
                .graphicsLayer {
                    translationX = offsetX
                    scaleX = scale
                    scaleY = scale
                    alpha = (1f - abs(offsetX) / widthPx * 1.3f).coerceIn(0f, 1f)
                }
                .playerSharedElement(PlayerSharedKeys.Artwork, transition)
                .shadow(elevation = 16.dp, shape = shape, clip = false),
        )
    }
}
