package com.spautifaille.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.components.TrackListPlaceholder
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

/** Actions de la découverte, partagées par la section de l'accueil et l'écran complet. */
@Immutable
data class DiscoveryActions(
    val onRefresh: () -> Unit = {},
    val onPlayAll: (shuffle: Boolean) -> Unit = {},
    val onPlayFrom: (index: Int) -> Unit = {},
)

@Composable
fun DiscoveryScreenRoot(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenArtist: ((artistUrl: String) -> Unit)? = null,
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        DiscoveryActions(
            onRefresh = viewModel::refresh,
            onPlayAll = viewModel::playAll,
            onPlayFrom = viewModel::playFrom,
        )
    }
    DiscoveryScreen(
        state = state,
        actions = actions,
        onBack = onBack,
        onOpenArtist = onOpenArtist,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    state: DiscoveryUiState,
    actions: DiscoveryActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenArtist: ((artistUrl: String) -> Unit)? = null,
) {
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.disc_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                    }
                },
                actions = {
                    IconButton(onClick = actions.onRefresh, enabled = !state.isRefreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.disc_refresh))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.status) {
                DiscoveryStatus.LOADING -> TrackListPlaceholder()
                DiscoveryStatus.EMPTY -> ScrollableBox {
                    EmptyState(
                        title = stringResource(R.string.disc_empty_title),
                        message = stringResource(R.string.disc_empty_message),
                        icon = Icons.Filled.AutoAwesome,
                        action = {
                            FilledTonalButton(onClick = actions.onRefresh) { Text(stringResource(R.string.disc_refresh)) }
                        },
                    )
                }
                DiscoveryStatus.ERROR -> ScrollableBox {
                    ErrorState(
                        message = (state.error ?: AppError.Unknown(null)).toMessage(),
                        onRetry = actions.onRefresh,
                    )
                }
                DiscoveryStatus.CONTENT -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = ListBottomPadding),
                ) {
                    item(key = "header") {
                        Header(count = state.tracks.size, onPlayAll = actions.onPlayAll)
                    }
                    itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
                        TrackListItem(
                            track = track,
                            onClick = { actions.onPlayFrom(index) },
                            onMoreClick = { actionsTrack = track },
                            modifier = Modifier.padding(horizontal = Spacing.s),
                        )
                    }
                }
            }
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            onDismiss = { actionsTrack = null },
            onGoToArtist = onOpenArtist,
        )
    }
}

/** Contenu défilant, pour que le geste de « pull to refresh » fonctionne aussi sur les états vides / erreur. */
@Composable
private fun ScrollableBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Header(count: Int, onPlayAll: (shuffle: Boolean) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                text = pluralStringResource(R.plurals.disc_track_count, count, count),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.disc_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PlayButtons(onPlayAll = onPlayAll)
    }
}

/** Boutons « Tout lire » / « Aléatoire » (réutilisés par la section de l'accueil). */
@Composable
internal fun PlayButtons(onPlayAll: (shuffle: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalAppHaptics.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Button(onClick = {
            haptics.click()
            onPlayAll(false)
        }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.disc_play_all), modifier = Modifier.padding(start = Spacing.s))
        }
        FilledTonalButton(onClick = {
            haptics.click()
            onPlayAll(true)
        }) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.disc_shuffle), modifier = Modifier.padding(start = Spacing.s))
        }
    }
}

private val PreviewTracks = List(8) {
    Track(id = "id$it", title = "Titre suggéré $it", artist = "Artiste $it", durationMs = 200_000L + it * 1_000L)
}

@Preview(showBackground = true)
@Composable
private fun DiscoveryScreenPreview() {
    SpautifailleTheme(dynamicColor = false) {
        DiscoveryScreen(
            state = DiscoveryUiState(tracks = PreviewTracks, isLoading = false),
            actions = DiscoveryActions(),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DiscoveryScreenEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        DiscoveryScreen(state = DiscoveryUiState(isLoading = false), actions = DiscoveryActions(), onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun DiscoveryScreenErrorPreview() {
    SpautifailleTheme(dynamicColor = false) {
        DiscoveryScreen(
            state = DiscoveryUiState(isLoading = false, error = AppError.Network),
            actions = DiscoveryActions(),
            onBack = {},
        )
    }
}
