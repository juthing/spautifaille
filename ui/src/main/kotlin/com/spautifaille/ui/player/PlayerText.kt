package com.spautifaille.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

private const val TEXT_CROSSFADE_MS = 250
private const val MARQUEE_INITIAL_DELAY_MS = 2_000
private const val MARQUEE_REPEAT_DELAY_MS = 2_000

/**
 * Ligne de texte du lecteur (titre, artiste) : fondu enchaîné quand [text] change (changement de titre) et, si
 * [marquee], défilement horizontal lent quand le texte est trop long (sinon points de suspension). Le premier
 * affichage n'est pas animé.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CrossfadeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
    marquee: Boolean = false,
) {
    Crossfade(
        targetState = text,
        modifier = modifier,
        animationSpec = tween(TEXT_CROSSFADE_MS),
        label = "trackText",
    ) { value ->
        Text(
            text = value,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (marquee) {
                Modifier
                    .fillMaxWidth()
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = MARQUEE_INITIAL_DELAY_MS,
                        repeatDelayMillis = MARQUEE_REPEAT_DELAY_MS,
                    )
            } else {
                Modifier
            },
        )
    }
}
