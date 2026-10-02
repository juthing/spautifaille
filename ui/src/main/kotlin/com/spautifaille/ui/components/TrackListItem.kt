package com.spautifaille.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Ligne de titre : pochette arrondie, titre, « artiste · durée » et menu d'actions.
 *
 * - [isCurrent] : titre de la file en cours, mis en évidence (fond légèrement teinté de la couleur primaire, titre
 *   en couleur primaire, égaliseur animé sur la pochette) ;
 * - [isPlaying] : n'a d'effet qu'avec [isCurrent] ; anime l'égaliseur (sinon il est figé) ;
 * - [isDownloaded] : affiche une petite icône « téléchargé » devant le sous-titre ;
 * - [isAvailable] : `false` = titre injouable (par exemple hors ligne) : contenu estompé à 38 % (le bouton ⋮ reste
 *   actif). Le clic reste transmis : à l'appelant de l'ignorer ou d'expliquer pourquoi ;
 * - [selectionMode] / [isSelected] : sélection multiple : le ⋮ est remplacé par une case à cocher et la ligne
 *   sélectionnée prend le fond `secondaryContainer` ;
 * - [onLongClick] : appui long (avec retour haptique). Par défaut ouvre le même menu que [onMoreClick] ;
 *   passer `null` pour désactiver l'appui long (par exemple quand un parent gère « appui long + glisser ») ;
 * - [clickFeedback] : retour haptique au toucher de la ligne (désactiver quand l'appelant fournit lui-même
 *   un retour, par exemple un refus sur un titre indisponible). Les appelants n'ajoutent pas leur propre `click()`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isDownloaded: Boolean = false,
    isAvailable: Boolean = true,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = onMoreClick,
    clickFeedback: Boolean = true,
) {
    val haptics = LocalAppHaptics.current
    val subtitle = track.durationMs?.let { stringResource(R.string.common_track_subtitle, track.artist, formatDuration(it)) }
        ?: track.artist
    val colors = MaterialTheme.colorScheme
    val contentAlpha = if (isAvailable) 1f else UnavailableAlpha
    val highlighted = selectionMode && isSelected
    val containerColor = when {
        highlighted -> colors.secondaryContainer
        isCurrent -> colors.primary.copy(alpha = CurrentContainerAlpha)
        else -> Color.Transparent
    }
    val headlineColor = when {
        highlighted -> colors.onSecondaryContainer
        isCurrent -> colors.primary
        else -> colors.onSurface
    }
    ListItem(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .semantics { if (selectionMode) selected = isSelected }
            .combinedClickable(
                onClick = {
                    if (clickFeedback) haptics.click()
                    onClick()
                },
                onLongClick = onLongClick?.let { longClick ->
                    {
                        haptics.longPress()
                        longClick()
                    }
                },
            ),
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            headlineColor = headlineColor,
            supportingColor = if (highlighted) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
            trailingIconColor = colors.onSurfaceVariant,
        ),
        leadingContent = {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.alpha(contentAlpha)) {
                Artwork(url = track.thumbnailUrl, modifier = Modifier.size(ArtworkSize.Row))
                if (isCurrent) {
                    NowPlayingOverlay(isPlaying = isPlaying, modifier = Modifier.size(ArtworkSize.Row))
                }
            }
        },
        headlineContent = {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.alpha(contentAlpha),
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(contentAlpha)) {
                if (isDownloaded) {
                    Icon(
                        imageVector = Icons.Filled.DownloadDone,
                        contentDescription = stringResource(R.string.lib_downloaded_indicator),
                        modifier = Modifier.size(DownloadedIconSize),
                        tint = colors.primary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text(text = subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            if (selectionMode) {
                // Case purement indicative : toute la ligne est cliquable (onCheckedChange = null).
                Checkbox(checked = isSelected, onCheckedChange = null)
            } else {
                IconButton(onClick = {
                    haptics.click()
                    onMoreClick()
                }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_action_more))
                }
            }
        },
    )
}

/** Voile teinté (couleur de surface du thème) + indicateur animé par-dessus une pochette. */
@Composable
private fun NowPlayingOverlay(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        NowPlayingIndicator(isAnimating = isPlaying)
    }
}

/**
 * Indicateur « en cours de lecture » : trois barres d'égaliseur qui oscillent (durées différentes, donc déphasées)
 * quand [isAnimating], et qui **se figent sur place** dès qu'il passe à `false` (lecture en pause) : plus aucune
 * animation ne tourne dans ce cas. À placer sur une pochette (voir [TrackListItem]) ou à côté d'un titre.
 */
@Composable
fun NowPlayingIndicator(
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val bars = remember { BarSpecs.map { Animatable(it.rest) } }
    LaunchedEffect(isAnimating) {
        // Quand l'effet est annulé (pause), chaque Animatable s'arrête là où il est : les barres restent figées.
        if (!isAnimating) return@LaunchedEffect
        coroutineScope {
            bars.forEachIndexed { index, bar ->
                launch {
                    val spec = BarSpecs[index]
                    var up = bar.value < spec.peak - BarEpsilon
                    while (true) {
                        bar.animateTo(
                            targetValue = if (up) spec.peak else spec.low,
                            animationSpec = tween(spec.durationMs, easing = FastOutSlowInEasing),
                        )
                        up = !up
                    }
                }
            }
        }
    }
    EqualizerBars(modifier, color) { index -> bars[index].value }
}

@Composable
private fun EqualizerBars(modifier: Modifier, color: Color, fraction: (Int) -> Float) {
    Row(
        modifier = modifier
            .height(BarsHeight)
            .width(BarsWidth),
        horizontalArrangement = Arrangement.spacedBy(BarGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(BarSpecs.size) { index ->
            Box(
                Modifier
                    .width(BarWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = fraction(index)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(color),
            )
        }
    }
}

/** Barre d'égaliseur : hauteur relative initiale (celle qu'on voit figée), bornes de l'oscillation, durée d'un aller. */
private class BarSpec(val rest: Float, val low: Float, val peak: Float, val durationMs: Int)

private val BarSpecs = listOf(
    BarSpec(rest = 0.55f, low = 0.25f, peak = 0.85f, durationMs = 460),
    BarSpec(rest = 0.9f, low = 0.35f, peak = 1f, durationMs = 620),
    BarSpec(rest = 0.4f, low = 0.2f, peak = 0.75f, durationMs = 520),
)

private const val BarEpsilon = 0.01f
private const val UnavailableAlpha = 0.38f
private const val CurrentContainerAlpha = 0.10f
private val DownloadedIconSize = 16.dp
private val BarsHeight = 18.dp
private val BarWidth = 4.dp
private val BarGap = 3.dp
private val BarsWidth = BarWidth * 3 + BarGap * 2

@Preview(showBackground = true)
@Composable
private fun TrackListItemPreview() {
    SpautifailleTheme(dynamicColor = false) {
        val track = Track(id = "a", title = "Un très long titre de morceau qui déborde de la ligne", artist = "Artiste", durationMs = 215_000)
        Column {
            TrackListItem(track, onClick = {}, onMoreClick = {})
            TrackListItem(track, onClick = {}, onMoreClick = {}, isCurrent = true, isPlaying = true)
            TrackListItem(track, onClick = {}, onMoreClick = {}, isCurrent = true, isPlaying = false)
            TrackListItem(track, onClick = {}, onMoreClick = {}, isAvailable = false)
            TrackListItem(track, onClick = {}, onMoreClick = {}, selectionMode = true, isSelected = true)
            TrackListItem(track, onClick = {}, onMoreClick = {}, selectionMode = true)
        }
    }
}
