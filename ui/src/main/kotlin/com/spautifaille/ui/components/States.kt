package com.spautifaille.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

/**
 * Mise en page commune des états plein écran : icône dans un cercle tonal, titre, message optionnel et action
 * optionnelle, centrés.
 */
@Composable
private fun StateLayout(
    icon: ImageVector,
    title: String?,
    modifier: Modifier = Modifier,
    message: String? = null,
    isError: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(StateIconCircleSize)
                .clip(CircleShape)
                .background(
                    if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(StateIconSize),
                tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(Spacing.m))
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
        if (message != null) {
            if (title != null) Spacer(Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = if (title != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Spacing.l))
            action()
        }
    }
}

private val StateIconCircleSize = 88.dp
private val StateIconSize = 40.dp

/**
 * État vide générique : icône tonale, titre, message optionnel et action optionnelle.
 * L'action peut être un composable libre ([action]) ou, plus simplement, un bouton tonal ([actionLabel] +
 * [onAction]) ; [action] prime s'il est fourni.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.Filled.Inbox,
    action: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val haptics = LocalAppHaptics.current
    val resolvedAction: (@Composable () -> Unit)? = action ?: if (actionLabel != null && onAction != null) {
        {
            FilledTonalButton(onClick = {
                haptics.click()
                onAction()
            }) { Text(actionLabel) }
        }
    } else {
        null
    }
    StateLayout(icon = icon, title = title, modifier = modifier, message = message, action = resolvedAction)
}

/**
 * État d'erreur : [message] (ressource issue de `AppError.toMessage()`), [title] optionnel au-dessus et
 * bouton « Réessayer ».
 */
@Composable
fun ErrorState(
    @StringRes message: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes title: Int? = null,
) {
    ErrorState(
        message = stringResource(message),
        onRetry = onRetry,
        modifier = modifier,
        title = title?.let { stringResource(it) },
    )
}

/** Variante de [ErrorState] avec un message déjà résolu. */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val haptics = LocalAppHaptics.current
    StateLayout(
        icon = Icons.Filled.ErrorOutline,
        title = title,
        message = message,
        isError = true,
        modifier = modifier,
        action = {
            FilledTonalButton(onClick = {
                haptics.click()
                onRetry()
            }) { Text(stringResource(R.string.common_action_retry)) }
        },
    )
}

/** État de chargement plein écran : indicateur de progression centré. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Bloc grisé pulsant, brique de base des placeholders de chargement. */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha.value }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** Placeholder de liste de titres pendant le chargement. */
@Composable
fun TrackListPlaceholder(
    modifier: Modifier = Modifier,
    count: Int = 8,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(Modifier.size(ArtworkSize.Row), shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.size(Spacing.m))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    ShimmerBox(Modifier.fillMaxWidth(0.6f).height(14.dp))
                    ShimmerBox(Modifier.fillMaxWidth(0.35f).height(12.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatesPreview() {
    SpautifailleTheme(dynamicColor = false) {
        Column {
            Box(Modifier.height(240.dp)) { EmptyState(title = "Aucun résultat", message = "Essayez une autre recherche") }
            Box(Modifier.height(240.dp)) { ErrorState(message = R.string.apperror_network, onRetry = {}) }
            TrackListPlaceholder(count = 3)
        }
    }
}
