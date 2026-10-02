package com.spautifaille.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
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

/**
 * Ligne de titre : pochette arrondie, titre, « artiste · durée » et menu d'actions.
 *
 * - [isCurrent] : titre de la file en cours, mis en évidence (fond tonal, titre en gras, indicateur sur la pochette) ;
 * - [isPlaying] : n'a d'effet qu'avec [isCurrent] ; anime l'indicateur « en cours de lecture » (sinon il est figé) ;
 * - [isDownloaded] : affiche une petite icône « téléchargé » devant le sous-titre ;
 * - [onLongClick] : appui long (avec retour haptique). Par défaut ouvre le même menu que [onMoreClick] ;
 *   passer `null` pour désactiver l'appui long ;
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
    onLongClick: (() -> Unit)? = onMoreClick,
    clickFeedback: Boolean = true,
) {
    val haptics = LocalAppHaptics.current
    val subtitle = track.durationMs?.let { stringResource(R.string.common_track_subtitle, track.artist, formatDuration(it)) }
        ?: track.artist
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
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
            containerColor = if (isCurrent) colors.secondaryContainer else Color.Transparent,
            headlineColor = if (isCurrent) colors.onSecondaryContainer else colors.onSurface,
            supportingColor = if (isCurrent) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
            trailingIconColor = if (isCurrent) colors.onSecondaryContainer else colors.onSurfaceVariant,
        ),
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
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
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDownloaded) {
                    Icon(
                        imageVector = Icons.Filled.DownloadDone,
                        contentDescription = stringResource(R.string.lib_downloaded_indicator),
                        modifier = Modifier.size(DownloadedIconSize),
                        tint = if (isCurrent) colors.onSecondaryContainer else colors.primary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text(text = subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            IconButton(onClick = {
                haptics.click()
                onMoreClick()
            }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_action_more))
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
 * Indicateur « en cours de lecture » : trois barres d'égaliseur qui oscillent quand [isAnimating],
 * figées sinon (aucune animation tournante dans ce cas). À placer sur une pochette (voir [TrackListItem])
 * ou à côté d'un titre.
 */
@Composable
fun NowPlayingIndicator(
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (isAnimating) {
        val transition = rememberInfiniteTransition(label = "nowPlaying")
        val bars: List<State<Float>> = BarDurationsMillis.mapIndexed { index, duration ->
            transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse),
                label = "bar$index",
            )
        }
        EqualizerBars(modifier, color) { index -> bars[index].value }
    } else {
        EqualizerBars(modifier, color) { StoppedBarFraction }
    }
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
        repeat(BarDurationsMillis.size) { index ->
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

private val DownloadedIconSize = 16.dp
private const val StoppedBarFraction = 0.6f
private val BarDurationsMillis = listOf(420, 560, 480)
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
        }
    }
}
