package com.spautifaille.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.spautifaille.ui.theme.deriveArtworkColors

private const val COLOR_ANIMATION_MS = 600

/**
 * Applique à [content] un thème M3 local dont l'accent suit [seed] (couleurs de `deriveArtworkColors`, les mêmes
 * que celles du thème « Musique en cours » de l'application), avec animation à chaque changement de titre.
 * [content] reçoit une lambda donnant le haut du dégradé de fond, à lire dans la phase de dessin (`drawBehind`)
 * pour que l'animation ne recompose pas l'écran.
 */
@Composable
internal fun PlayerColorScheme(seed: Color?, content: @Composable (backgroundTop: () -> Color) -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.surface.luminance() < 0.5f
    val target = remember(seed, base, dark) { deriveArtworkColors(seed, base, dark) }
    val spec = tween<Color>(COLOR_ANIMATION_MS)
    val top = animateColorAsState(target.backgroundTop, spec, label = "playerTop")
    val primary by animateColorAsState(target.primary, spec, label = "playerPrimary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "playerOnPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "playerContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "playerOnContainer")
    val scheme = base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        content { top.value }
    }
}
