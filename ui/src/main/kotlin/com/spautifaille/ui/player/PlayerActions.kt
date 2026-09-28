package com.spautifaille.ui.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** Callbacks du lecteur plein écran, regroupés pour garder des signatures lisibles. */
@Immutable
data class PlayerActions(
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onSeek: (positionMs: Long) -> Unit = {},
    val onToggleShuffle: () -> Unit = {},
    val onCycleRepeat: () -> Unit = {},
    val onToggleLike: () -> Unit = {},
    val onSetSpeed: (Float) -> Unit = {},
    val onSleepMinutes: (Int) -> Unit = {},
    val onSleepEndOfTrack: () -> Unit = {},
    val onCancelSleep: () -> Unit = {},
    val onSkipToQueueItem: (Int) -> Unit = {},
    val onMoveQueueItem: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onRemoveQueueItem: (Int) -> Unit = {},
    val onClearQueue: () -> Unit = {},
)

/** Clés des éléments partagés entre le mini lecteur et le lecteur plein écran. */
object PlayerSharedKeys {
    const val Container = "player-container"
    const val Artwork = "player-artwork"
    const val Title = "player-title"
}

/** Portées nécessaires aux transitions d'éléments partagés. Nul en prévisualisation. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Immutable
class PlayerTransition(
    val sharedScope: SharedTransitionScope,
    val visibilityScope: AnimatedVisibilityScope,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerSharedElement(key: String, transition: PlayerTransition?): Modifier {
    if (transition == null) return this
    return with(transition.sharedScope) {
        this@playerSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = transition.visibilityScope,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerSharedBounds(
    key: String,
    transition: PlayerTransition?,
    clipShape: Shape = RoundedCornerShape(0),
): Modifier {
    if (transition == null) return this
    return with(transition.sharedScope) {
        this@playerSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = transition.visibilityScope,
            clipInOverlayDuringTransition = OverlayClip(clipShape),
        )
    }
}
