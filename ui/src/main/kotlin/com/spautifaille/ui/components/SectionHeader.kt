package com.spautifaille.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

/**
 * En-tête de section d'un écran (« Récemment écoutés », « Playlists »…). Marges horizontales
 * ([ScreenHorizontalPadding]) incluses. Le bouton d'action (« Tout voir ») n'apparaît que si [actionLabel]
 * et [onAction] sont tous deux fournis.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val haptics = LocalAppHaptics.current
    val hasAction = actionLabel != null && onAction != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Le bouton texte a déjà une marge interne : on réduit celle du bord pour aligner son libellé.
            .padding(start = ScreenHorizontalPadding, end = if (hasAction) ScreenHorizontalPadding - Spacing.s else ScreenHorizontalPadding)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = {
                haptics.click()
                onAction()
            }) {
                Text(actionLabel)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SectionHeaderPreview() {
    SpautifailleTheme(dynamicColor = false) {
        androidx.compose.foundation.layout.Column {
            SectionHeader(title = "Récemment écoutés")
            SectionHeader(title = "Découvertes", actionLabel = "Tout voir", onAction = {})
        }
    }
}
