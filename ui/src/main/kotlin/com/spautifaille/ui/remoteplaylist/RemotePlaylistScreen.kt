package com.spautifaille.ui.remoteplaylist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.LoadingState
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Nombre d'éléments restants avant la fin de liste déclenchant le chargement de la page suivante. */
private const val PREFETCH_DISTANCE = 6

/** Taille de la pochette dans l'en-tête. */
private val HeaderCoverSize = 200.dp

@Immutable
data class RemotePlaylistActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onPlayFrom: (Int) -> Unit = {},
    val onPlayAll: (shuffle: Boolean) -> Unit = {},
    val onSave: () -> Unit = {},
)

/**
 * Playlist YouTube distante (lecture, enregistrement dans la bibliothèque).
 * Argument de navigation lu par le ViewModel : `SavedStateHandle["url"]` (String).
 */
@Composable
fun RemotePlaylistRoute(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RemotePlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val haptics = LocalAppHaptics.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                when (event) {
                    is RemotePlaylistEvent.Saved -> {
                        haptics.confirm()
                        val result = snackbarHostState.showSnackbar(
                            message = resources.getString(R.string.misc_remote_saved_message, event.name),
                            actionLabel = resources.getString(R.string.misc_open),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) onOpenPlaylist(event.playlistId)
                    }
                    is RemotePlaylistEvent.SaveFailed -> {
                        haptics.reject()
                        snackbarHostState.showSnackbar(resources.getString(event.error.toMessage()))
                    }
                }
            }
        }
    }

    val actions = remember(viewModel, onBack) {
        RemotePlaylistActions(
            onBack = onBack,
            onRetry = viewModel::load,
            onLoadMore = viewModel::loadMore,
            onPlayFrom = viewModel::playFrom,
            onPlayAll = viewModel::playAll,
            onSave = viewModel::saveToLibrary,
        )
    }
    RemotePlaylistScreen(state, actions, snackbarHostState, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotePlaylistScreen(
    state: RemotePlaylistUiState,
    actions: RemotePlaylistActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Le titre est déjà dans l'en-tête : il n'apparaît dans la barre qu'une fois l'en-tête sorti de l'écran.
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var actionsTrack by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    if (showTitle) Text(state.playlist?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val status = state.status) {
            RemotePlaylistStatus.Loading -> LoadingState(Modifier.padding(innerPadding))
            is RemotePlaylistStatus.Error -> ErrorState(
                title = stringResource(R.string.misc_remote_load_failed),
                message = stringResource(status.error.toMessage()),
                onRetry = actions.onRetry,
                modifier = Modifier.padding(innerPadding),
            )
            RemotePlaylistStatus.Content -> RemotePlaylistContent(
                state = state,
                actions = actions,
                listState = listState,
                innerPadding = innerPadding,
                onTrackMore = { actionsTrack = it },
            )
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(track = track, onDismiss = { actionsTrack = null })
    }
}

@Composable
private fun RemotePlaylistContent(
    state: RemotePlaylistUiState,
    actions: RemotePlaylistActions,
    listState: LazyListState,
    innerPadding: PaddingValues,
    onTrackMore: (Track) -> Unit,
) {
    LoadMoreEffect(listState, enabled = state.hasMore && !state.isLoadingMore && state.loadMoreError == null, actions.onLoadMore)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + ListBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "header") {
            RemotePlaylistHeader(state = state, actions = actions, topInset = innerPadding.calculateTopPadding())
        }
        itemsIndexed(state.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
            TrackListItem(
                track = track,
                onClick = { actions.onPlayFrom(index) },
                onMoreClick = { onTrackMore(track) },
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s),
            )
        }
        if (state.isLoadingMore) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(Spacing.m), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        } else if (state.loadMoreError != null) {
            item(key = "load-more-error") {
                Row(
                    Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(state.loadMoreError.toMessage()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = actions.onLoadMore) { Text(stringResource(R.string.common_action_retry)) }
                }
            }
        }
    }
}

/** Déclenche [onLoadMore] quand le dernier élément visible approche de la fin de la liste. */
@Composable
private fun LoadMoreEffect(listState: LazyListState, enabled: Boolean, onLoadMore: () -> Unit) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - PREFETCH_DISTANCE
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { currentOnLoadMore() }
    }
}

/** En-tête : dégradé tonal, grande pochette, titre, auteur et nombre de titres, puis Lecture / Aléatoire / Enregistrer. */
@Composable
private fun RemotePlaylistHeader(
    state: RemotePlaylistUiState,
    actions: RemotePlaylistActions,
    topInset: Dp,
) {
    val playlist = state.playlist ?: return
    val haptics = LocalAppHaptics.current
    val colors = MaterialTheme.colorScheme
    val hasTracks = state.tracks.isNotEmpty()
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surface))),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset + Spacing.s, bottom = Spacing.m)
                .padding(horizontal = ScreenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Artwork(
                url = playlist.thumbnailUrl,
                modifier = Modifier.size(HeaderCoverSize),
                shape = MaterialTheme.shapes.large,
                contentDescription = stringResource(R.string.misc_remote_cover_description, playlist.name),
            )
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = Spacing.m)
                    .semantics { heading() },
            )
            val count = playlist.trackCount?.toInt() ?: state.tracks.size
            val countText = pluralStringResource(R.plurals.common_track_count, count, count)
            Text(
                text = listOfNotNull(playlist.uploader, countText).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(top = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Button(
                    onClick = {
                        haptics.click()
                        actions.onPlayAll(false)
                    },
                    enabled = hasTracks,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.common_action_play), Modifier.padding(start = Spacing.s))
                }
                FilledTonalButton(
                    onClick = {
                        haptics.click()
                        actions.onPlayAll(true)
                    },
                    enabled = hasTracks,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.misc_action_shuffle), Modifier.padding(start = Spacing.s))
                }
            }
            OutlinedButton(
                onClick = {
                    haptics.click()
                    actions.onSave()
                },
                enabled = !state.isSaving && hasTracks,
            ) {
                Icon(Icons.Filled.LibraryAdd, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.misc_remote_save_to_library), Modifier.padding(start = Spacing.s))
            }
            if (state.isSaving) {
                Column(
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    val target = state.saveTarget
                    if (target != null && target > 0) {
                        LinearProgressIndicator(
                            progress = { (state.saveProgress.toFloat() / target).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        text = stringResource(R.string.misc_remote_saving_progress, state.saveProgress),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// region Previews

private fun previewRemoteState(
    status: RemotePlaylistStatus = RemotePlaylistStatus.Content,
    saving: Boolean = false,
) = RemotePlaylistUiState(
    status = status,
    playlist = RemotePlaylist("https://youtube.com/playlist?list=x", "Mix du dimanche", "Chaîne Exemple", null, 120),
    tracks = (1..12).map { Track("id$it", "Titre numéro $it", "Artiste $it", durationMs = 190_000L) },
    hasMore = true,
    isSaving = saving,
    saveProgress = 40,
    saveTarget = 120,
)

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RemotePlaylistPreview() {
    SpautifailleTheme(dynamicColor = false) {
        RemotePlaylistScreen(previewRemoteState(), RemotePlaylistActions(), remember { SnackbarHostState() })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RemotePlaylistSavingPreview() {
    SpautifailleTheme(dynamicColor = false) {
        RemotePlaylistScreen(previewRemoteState(saving = true), RemotePlaylistActions(), remember { SnackbarHostState() })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RemotePlaylistErrorPreview() {
    SpautifailleTheme(dynamicColor = false) {
        RemotePlaylistScreen(
            RemotePlaylistUiState(status = RemotePlaylistStatus.Error(AppError.Network)),
            RemotePlaylistActions(),
            remember { SnackbarHostState() },
        )
    }
}

// endregion
