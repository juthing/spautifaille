package com.spautifaille.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs

/** Issue d'un glissement horizontal sur la pochette ou le mini lecteur. */
internal enum class SwipeResult { None, Previous, Next }

/**
 * Décide de l'issue d'un glissement horizontal au relâchement. Un glissement vers la droite
 * ([offsetPx] > 0) revient au titre précédent, vers la gauche passe au suivant. Le geste est validé s'il
 * dépasse [thresholdPx], ou s'il est un geste vif ([flingVelocityPx]) dans le sens du déplacement.
 */
internal fun resolveHorizontalSwipe(
    offsetPx: Float,
    velocityPx: Float,
    thresholdPx: Float,
    flingVelocityPx: Float,
): SwipeResult {
    if (offsetPx == 0f) return SwipeResult.None
    val sameDirection = (velocityPx > 0f) == (offsetPx > 0f)
    val committed = abs(offsetPx) >= thresholdPx ||
        (abs(velocityPx) >= flingVelocityPx && sameDirection && abs(offsetPx) >= thresholdPx * MIN_FLING_FRACTION)
    return when {
        !committed -> SwipeResult.None
        offsetPx > 0f -> SwipeResult.Previous
        else -> SwipeResult.Next
    }
}

/** Vrai si un glissement vers le bas de [offsetPx] doit refermer le lecteur plein écran. */
internal fun shouldCollapsePlayer(
    offsetPx: Float,
    velocityPx: Float,
    thresholdPx: Float,
    flingVelocityPx: Float,
): Boolean = offsetPx >= thresholdPx || (velocityPx >= flingVelocityPx && offsetPx > 0f)

/**
 * Empêche un glissement commencé sur cet élément (barre de progression, boutons de commande) de remonter
 * vers le geste de fermeture du lecteur plein écran : les déplacements sont marqués comme consommés après
 * le passage des enfants, donc le `draggable` parent ne les voit jamais. Appuis et relâchements restent intacts.
 */
internal fun Modifier.blockParentDrag(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            event.changes.fastForEach { change ->
                if (change.positionChanged()) change.consume()
            }
        } while (event.changes.fastAny { it.pressed })
    }
}

/** Pas (ms) des crans haptiques pendant le glissement de la barre de progression. */
internal const val SEEK_HAPTIC_STEP_MS = 10_000L

/** Index du cran haptique correspondant à [positionMs] : un changement d'index déclenche un tick. */
internal fun seekHapticBucket(positionMs: Long): Long = positionMs.coerceAtLeast(0L) / SEEK_HAPTIC_STEP_MS

private const val MIN_FLING_FRACTION = 0.25f
