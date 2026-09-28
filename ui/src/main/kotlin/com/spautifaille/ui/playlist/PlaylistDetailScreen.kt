package com.spautifaille.ui.playlist

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.library.LibraryArtwork
import com.spautifaille.ui.library.LibraryConfirmDialog
import com.spautifaille.ui.library.LibraryContentMaxWidth
import com.spautifaille.ui.library.LibraryEmptyState
import com.spautifaille.ui.library.LibraryLikedArtwork
import com.spautifaille.ui.library.LibraryTrackRow
import com.spautifaille.ui.library.PlaylistNameDialog
import com.spautifaille.ui.library.totalDurationText
import com.spautifaille.ui.library.trackCountText
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Actions de l'écran détail de playlist. Valeurs par défaut vides pour les previews. */
@Immutable
data class PlaylistDetailActions(
    val onBack: () -> Unit = {},
    val onOpenArtist: (String) -> Unit = {},
    val onPlayFrom: (Int) -> Unit = {},
    val onPlayAll: (shuffle: Boolean) -> Unit = {},
    val onDownloadAll: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onDragEnd: () -> Unit = {},
    val onRemove: (PlaylistEntry) -> Unit = {},
    val onPlayNext: (Track) -> Unit = {},
    val onAddToQueue: (Track) -> Unit = {},
)

/**
 * Détail d'une playlist locale.
 * Argument de navigation lu par le ViewModel : `SavedStateHandle["id"]` (Long).
 */
@Composable
fun PlaylistDetailRoute(
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlaylistDetailEvent.TrackRemoved -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.lib_track_removed, event.track.title),
                        actionLabel = resources.getString(R.string.lib_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove(event.track, event.position)
                }
                is PlaylistDetailEvent.DownloadsQueued -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        resources.getQuantityString(R.plurals.lib_downloads_queued, event.count, event.count),
                    )
                }
                is PlaylistDetailEvent.PlayNextQueued -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(resources.getString(R.string.lib_play_next_done))
                }
                is PlaylistDetailEvent.AddedToQueue -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(resources.getString(R.string.lib_added_to_queue))
                }
                PlaylistDetailEvent.PlaylistDeleted -> onBack()
            }
        }
    }

    val actions = remember(viewModel, onBack, onOpenArtist) {
        PlaylistDetailActions(
            onBack = onBack,
            onOpenArtist = onOpenArtist,
            onPlayFrom = viewModel::playFrom,
            onPlayAll = viewModel::playAll,
            onDownloadAll = viewModel::downloadAll,
            onRename = { viewModel.rename(it) },
            onDelete = { viewModel.delete() },
            onMove = viewModel::onMove,
            onDragEnd = viewModel::onDragEnd,
            onRemove = viewModel::removeEntry,
            onPlayNext = viewModel::playNext,
            onAddToQueue = viewModel::addToQueue,
        )
    }
    PlaylistDetailScreen(
        state = state,
        actions = actions,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailUiState,
    actions: PlaylistDetailActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val playlist = state.playlist

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lib_back),
                        )
                    }
                },
                actions = {
                    if (playlist != null && !playlist.isSystem) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.lib_more_options),
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lib_rename)) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        showRename = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lib_delete)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        showDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                playlist == null -> LibraryEmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = stringResource(R.string.lib_playlist_not_found),
                    body = stringResource(R.string.lib_playlist_not_found_body),
                    actionLabel = stringResource(R.string.lib_back),
                    onAction = actions.onBack,
                )
                else -> PlaylistContent(state, playlist, actions)
            }
        }
    }

    if (showRename && playlist != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.lib_rename_playlist),
            confirmLabel = stringResource(R.string.lib_rename),
            initialName = playlist.name,
            onConfirm = {
                actions.onRename(it)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
    if (showDelete && playlist != null) {
        LibraryConfirmDialog(
            title = stringResource(R.string.lib_delete_playlist_title),
            text = stringResource(R.string.lib_delete_playlist_message, playlist.name),
            confirmLabel = stringResource(R.string.lib_delete),
            onConfirm = {
                actions.onDelete()
                showDelete = false
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun PlaylistContent(
    state: PlaylistDetailUiState,
    playlist: Playlist,
    actions: PlaylistDetailActions,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // Copie locale pendant le glissement : la liste réagit dans la même frame, sans attendre le StateFlow.
    var local by remember { mutableStateOf(state.entries) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = local.indexOfFirst { it.entryId == from.key }
        val toIndex = local.indexOfFirst { it.entryId == to.key }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            local = local.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            actions.onMove(fromIndex, toIndex)
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val dragging = reorderState.isAnyItemDragging
    LaunchedEffect(state.entries, dragging) { if (!dragging) local = state.entries }
    val entries = if (dragging) local else state.entries

    LazyColumn(
        state = listState,
        modifier = Modifier.widthIn(max = LibraryContentMaxWidth).fillMaxSize(),
    ) {
        item(key = HEADER_KEY) {
            PlaylistHeader(
                playlist = playlist,
                trackCount = entries.size,
                totalDurationMs = entries.sumOf { it.track.durationMs ?: 0L },
                canPlay = entries.isNotEmpty(),
                actions = actions,
            )
        }
        if (entries.isEmpty()) {
            item(key = EMPTY_KEY) {
                LibraryEmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                    title = stringResource(R.string.lib_playlist_empty_title),
                    body = stringResource(R.string.lib_playlist_empty_body),
                )
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.entryId }) { index, entry ->
            ReorderableItem(reorderState, key = entry.entryId) { isDragging ->
                PlaylistEntryRow(
                    entry = entry,
                    isDragging = isDragging,
                    dragHandle = {
                        IconButton(
                            modifier = Modifier.draggableHandle(
                                onDragStopped = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    actions.onDragEnd()
                                },
                            ),
                            onClick = {},
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = stringResource(R.string.lib_reorder),
                            )
                        }
                    },
                    actions = actions,
                    onPlay = { actions.onPlayFrom(index) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: Playlist,
    trackCount: Int,
    totalDurationMs: Long,
    canPlay: Boolean,
    actions: PlaylistDetailActions,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val artworkModifier = Modifier.size(192.dp)
        val artworkShape = MaterialTheme.shapes.large
        if (playlist.isSystem) {
            LibraryLikedArtwork(artworkModifier, artworkShape)
        } else {
            LibraryArtwork(playlist.thumbnailUrl, artworkModifier, artworkShape)
        }
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val summary = if (trackCount > 0) {
            "${trackCountText(trackCount)} · ${totalDurationText(totalDurationMs)}"
        } else {
            trackCountText(0)
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { actions.onPlayAll(false) }, enabled = canPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.lib_play), Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(onClick = { actions.onPlayAll(true) }, enabled = canPlay) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.lib_shuffle), Modifier.padding(start = 8.dp))
            }
            FilledTonalIconButton(onClick = actions.onDownloadAll, enabled = canPlay) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = stringResource(R.string.lib_download_all),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderableCollectionItemScope.PlaylistEntryRow(
    entry: PlaylistEntry,
    isDragging: Boolean,
    dragHandle: @Composable ReorderableCollectionItemScope.() -> Unit,
    actions: PlaylistDetailActions,
    onPlay: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) actions.onRemove(entry)
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Surface(
            tonalElevation = if (isDragging) 6.dp else 0.dp,
            shadowElevation = if (isDragging) 6.dp else 0.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            LibraryTrackRow(
                track = entry.track,
                onClick = onPlay,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackMenu(entry, actions)
                        dragHandle()
                    }
                },
            )
        }
    }
}

@Composable
private fun TrackMenu(entry: PlaylistEntry, actions: PlaylistDetailActions) {
    var open by remember { mutableStateOf(false) }
    val artistUrl = entry.track.artistUrl
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.lib_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_play_next)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onPlayNext(entry.track)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_add_to_queue)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onAddToQueue(entry.track)
                },
            )
            if (artistUrl != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lib_go_to_artist)) },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    onClick = {
                        open = false
                        actions.onOpenArtist(artistUrl)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_remove_from_playlist)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onRemove(entry)
                },
            )
        }
    }
}

private const val HEADER_KEY = "header"
private const val EMPTY_KEY = "empty"

// region Previews

private fun previewEntries(count: Int) = (1..count).map {
    PlaylistEntry(
        entryId = it.toLong(),
        position = it - 1,
        track = Track("id$it", "Titre numéro $it", "Artiste $it", "https://youtube.com/@a$it", durationMs = 200_000L + it * 5_000L),
    )
}

private fun previewState(system: Boolean = false, count: Int = 6) = PlaylistDetailUiState(
    isLoading = false,
    playlist = Playlist(
        id = if (system) Playlist.LIKED_ID else 5L,
        name = if (system) "Titres likés" else "Road trip",
        trackCount = count,
        thumbnailUrl = null,
        isSystem = system,
        createdAt = 0,
        updatedAt = 0,
    ),
    entries = previewEntries(count),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailPreview() {
    MaterialTheme {
        PlaylistDetailScreen(previewState(), PlaylistDetailActions(), remember { SnackbarHostState() })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailLikedPreview() {
    MaterialTheme {
        PlaylistDetailScreen(previewState(system = true), PlaylistDetailActions(), remember { SnackbarHostState() })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailEmptyPreview() {
    MaterialTheme {
        PlaylistDetailScreen(previewState(count = 0), PlaylistDetailActions(), remember { SnackbarHostState() })
    }
}

// endregion
