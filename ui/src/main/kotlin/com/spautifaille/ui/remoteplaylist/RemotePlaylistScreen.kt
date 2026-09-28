package com.spautifaille.ui.remoteplaylist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.library.LibraryArtwork
import com.spautifaille.ui.library.LibraryContentMaxWidth
import com.spautifaille.ui.library.LibraryEmptyState
import com.spautifaille.ui.library.LibraryTrackRow
import com.spautifaille.ui.library.libraryMessage
import com.spautifaille.ui.library.trackCountText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Nombre d'éléments restants avant la fin de liste déclenchant le chargement de la page suivante. */
private const val PREFETCH_DISTANCE = 6

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                when (event) {
                    is RemotePlaylistEvent.Saved -> {
                        val result = snackbarHostState.showSnackbar(
                            message = resources.getString(R.string.lib_saved_to_library, event.name),
                            actionLabel = resources.getString(R.string.lib_open),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) onOpenPlaylist(event.playlistId)
                    }
                    is RemotePlaylistEvent.SaveFailed -> snackbarHostState.showSnackbar(
                        resources.getString(event.error.libraryMessage()),
                    )
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
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.playlist?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lib_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            when (val status = state.status) {
                RemotePlaylistStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is RemotePlaylistStatus.Error -> LibraryEmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(R.string.lib_remote_load_failed),
                    body = stringResource(status.error.libraryMessage()),
                    actionLabel = stringResource(R.string.lib_retry),
                    onAction = actions.onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
                RemotePlaylistStatus.Content -> RemotePlaylistContent(state, actions)
            }
        }
    }
}

@Composable
private fun RemotePlaylistContent(state: RemotePlaylistUiState, actions: RemotePlaylistActions) {
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, enabled = state.hasMore && !state.isLoadingMore && state.loadMoreError == null, actions.onLoadMore)

    LazyColumn(state = listState, modifier = Modifier.widthIn(max = LibraryContentMaxWidth).fillMaxSize()) {
        item(key = "header") { RemotePlaylistHeader(state, actions) }
        itemsIndexed(state.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
            LibraryTrackRow(track, onClick = { actions.onPlayFrom(index) })
        }
        if (state.isLoadingMore) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        } else if (state.loadMoreError != null) {
            item(key = "load-more-error") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(state.loadMoreError.libraryMessage()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = actions.onLoadMore) { Text(stringResource(R.string.lib_retry)) }
                }
            }
        }
    }
}

/** Déclenche [onLoadMore] quand le dernier élément visible approche de la fin de la liste. */
@Composable
private fun LoadMoreEffect(listState: LazyListState, enabled: Boolean, onLoadMore: () -> Unit) {
    val currentOnLoadMore by androidx.compose.runtime.rememberUpdatedState(onLoadMore)
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

@Composable
private fun RemotePlaylistHeader(state: RemotePlaylistUiState, actions: RemotePlaylistActions) {
    val playlist = state.playlist ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LibraryArtwork(playlist.thumbnailUrl, Modifier.size(192.dp), MaterialTheme.shapes.large)
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val count = playlist.trackCount?.toInt() ?: state.tracks.size
        val subtitle = listOfNotNull(playlist.uploader, trackCountText(count)).joinToString(" · ")
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.onPlayAll(false) }, enabled = state.tracks.isNotEmpty()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.lib_play), Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(onClick = { actions.onPlayAll(true) }, enabled = state.tracks.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.lib_shuffle), Modifier.padding(start = 8.dp))
            }
        }
        OutlinedButton(
            onClick = actions.onSave,
            enabled = !state.isSaving && state.tracks.isNotEmpty(),
        ) {
            Icon(Icons.Filled.LibraryAdd, contentDescription = null, Modifier.size(18.dp))
            Text(stringResource(R.string.lib_save_to_library), Modifier.padding(start = 8.dp))
        }
        if (state.isSaving) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    text = stringResource(R.string.lib_saving_progress, state.saveProgress),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun RemotePlaylistPreview() {
    MaterialTheme {
        RemotePlaylistScreen(previewRemoteState(), RemotePlaylistActions(), remember { SnackbarHostState() })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun RemotePlaylistSavingPreview() {
    MaterialTheme {
        RemotePlaylistScreen(previewRemoteState(saving = true), RemotePlaylistActions(), remember { SnackbarHostState() })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun RemotePlaylistErrorPreview() {
    MaterialTheme {
        RemotePlaylistScreen(
            RemotePlaylistUiState(status = RemotePlaylistStatus.Error(AppError.Network)),
            RemotePlaylistActions(),
            remember { SnackbarHostState() },
        )
    }
}

// endregion
