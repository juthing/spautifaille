package com.spautifaille.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.SectionHeader
import com.spautifaille.ui.components.ShimmerBox
import com.spautifaille.ui.discovery.DiscoveryActions
import com.spautifaille.ui.discovery.DiscoveryStatus
import com.spautifaille.ui.discovery.DiscoveryUiState
import com.spautifaille.ui.discovery.PlayButtons
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing

/** Nombre de titres montrés dans le carrousel de l'accueil (la liste complète est derrière « Tout voir »). */
internal const val HOME_DISCOVERY_COUNT = 15

/**
 * Section « Découverte » de l'accueil : en-tête + « Tout voir », grandes cartes à bouton lecture,
 * « Tout lire » / « Aléatoire » / actualiser. [onTrackLongClick] ouvre les actions d'un titre.
 */
internal fun LazyListScope.discoverySection(
    state: DiscoveryUiState,
    actions: DiscoveryActions,
    onSeeAll: () -> Unit,
    onTrackLongClick: (Track) -> Unit,
) {
    item(key = "discovery") {
        DiscoverySection(state = state, actions = actions, onSeeAll = onSeeAll, onTrackLongClick = onTrackLongClick)
    }
}

@Composable
internal fun DiscoverySection(
    state: DiscoveryUiState,
    actions: DiscoveryActions,
    onSeeAll: () -> Unit,
    onTrackLongClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isContent = state.status == DiscoveryStatus.CONTENT
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            title = stringResource(R.string.disc_title),
            actionLabel = if (isContent) stringResource(R.string.home_see_all) else null,
            onAction = if (isContent) onSeeAll else null,
        )
        when (state.status) {
            DiscoveryStatus.LOADING -> DiscoveryPlaceholder()
            DiscoveryStatus.EMPTY -> InlineHint(
                icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                title = stringResource(R.string.disc_empty_title),
                message = stringResource(R.string.disc_empty_message),
            )
            DiscoveryStatus.ERROR -> InlineHint(
                icon = {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = stringResource(R.string.disc_error_title),
                message = stringResource((state.error ?: AppError.Unknown(null)).toMessage()),
                action = {
                    TextButton(onClick = actions.onRefresh) { Text(stringResource(R.string.common_action_retry)) }
                },
            )
            DiscoveryStatus.CONTENT -> {
                Text(
                    text = stringResource(R.string.disc_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    itemsIndexed(state.tracks.take(HOME_DISCOVERY_COUNT), key = { _, track -> track.id }) { index, track ->
                        TrackCard(
                            track = track,
                            size = ArtworkSize.CardLarge,
                            onClick = { actions.onPlayFrom(index) },
                            onLongClick = { onTrackLongClick(track) },
                            onPlayClick = { actions.onPlayFrom(index) },
                        )
                    }
                }
                DiscoveryActionsRow(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun DiscoveryActionsRow(state: DiscoveryUiState, actions: DiscoveryActions) {
    val haptics = LocalAppHaptics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenHorizontalPadding, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButtons(onPlayAll = actions.onPlayAll, modifier = Modifier.weight(1f))
        if (state.isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = Spacing.m)
                    .size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = {
                haptics.click()
                actions.onRefresh()
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.disc_refresh))
            }
        }
    }
}

@Composable
private fun DiscoveryPlaceholder() {
    Row(
        modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        repeat(2) {
            Column(Modifier.width(ArtworkSize.CardLarge)) {
                ShimmerBox(Modifier.size(ArtworkSize.CardLarge), shape = MaterialTheme.shapes.large)
                Spacer(Modifier.height(Spacing.s))
                ShimmerBox(Modifier.fillMaxWidth(0.8f).height(14.dp))
            }
        }
    }
}
