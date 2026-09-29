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
import com.spautifaille.ui.R
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.ShimmerBox
import com.spautifaille.ui.discovery.DiscoveryActions
import com.spautifaille.ui.discovery.DiscoveryStatus
import com.spautifaille.ui.discovery.DiscoveryUiState
import com.spautifaille.ui.discovery.PlayButtons

/** Nombre de titres montrés dans le carrousel de l'accueil (la liste complète est derrière « Voir tout »). */
internal const val HOME_DISCOVERY_COUNT = 15

/** Section « Découverte » de l'accueil : en-tête + actualiser, carrousel, « Tout lire » / « Aléatoire ». */
internal fun LazyListScope.discoverySection(
    state: DiscoveryUiState,
    actions: DiscoveryActions,
    onSeeAll: () -> Unit,
) {
    item(key = "discover-header") {
        DiscoveryHeader(state = state, onRefresh = actions.onRefresh, onSeeAll = onSeeAll)
    }
    when (state.status) {
        DiscoveryStatus.LOADING -> item(key = "discover-loading") { DiscoveryPlaceholder() }
        DiscoveryStatus.EMPTY -> item(key = "discover-empty") {
            InlineHint(
                icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                title = stringResource(R.string.disc_empty_title),
                message = stringResource(R.string.disc_empty_message),
            )
        }
        DiscoveryStatus.ERROR -> item(key = "discover-error") {
            InlineHint(
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
        }
        DiscoveryStatus.CONTENT -> {
            item(key = "discover-carousel") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(state.tracks.take(HOME_DISCOVERY_COUNT), key = { _, track -> track.id }) { index, track ->
                        RecentCard(track = track, onClick = { actions.onPlayFrom(index) })
                    }
                }
            }
            item(key = "discover-actions") {
                PlayButtons(
                    onPlayAll = actions.onPlayAll,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoveryHeader(state: DiscoveryUiState, onRefresh: () -> Unit, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.disc_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        )
        if (state.status == DiscoveryStatus.CONTENT) {
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.disc_see_all)) }
        }
        if (state.isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.disc_refresh))
            }
        }
    }
}

@Composable
private fun DiscoveryPlaceholder() {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Column(Modifier.width(144.dp)) {
                ShimmerBox(Modifier.size(144.dp), shape = MaterialTheme.shapes.large)
                Spacer(Modifier.height(8.dp))
                ShimmerBox(Modifier.fillMaxWidth(0.8f).height(14.dp))
            }
        }
    }
}
