package com.spautifaille.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spautifaille.ui.theme.SpautifailleTheme

/** Teinte d'un [ToneIconCircle] : paires conteneur / contenu du thème (aucune couleur en dur). */
enum class IconTone { Primary, Secondary, Tertiary, Error, Neutral }

@Composable
private fun IconTone.containerColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        IconTone.Primary -> scheme.primaryContainer
        IconTone.Secondary -> scheme.secondaryContainer
        IconTone.Tertiary -> scheme.tertiaryContainer
        IconTone.Error -> scheme.errorContainer
        IconTone.Neutral -> scheme.surfaceContainerHighest
    }
}

@Composable
private fun IconTone.contentColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        IconTone.Primary -> scheme.onPrimaryContainer
        IconTone.Secondary -> scheme.onSecondaryContainer
        IconTone.Tertiary -> scheme.onTertiaryContainer
        IconTone.Error -> scheme.onErrorContainer
        IconTone.Neutral -> scheme.onSurfaceVariant
    }
}

/**
 * Icône dans un cercle tonal coloré (lignes de réglages, cartes de choix). Décorative : le sens est porté par
 * le texte voisin, d'où l'absence de `contentDescription`.
 */
@Composable
fun ToneIconCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: IconTone = IconTone.Primary,
    size: Dp = 44.dp,
    iconSize: Dp = size / 2,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tone.containerColor()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tone.contentColor(),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Preview
@Composable
private fun ToneIconCirclePreview() {
    SpautifailleTheme(dynamicColor = false) {
        Row {
            IconTone.entries.forEach { ToneIconCircle(Icons.Filled.MusicNote, tone = it) }
        }
    }
}
