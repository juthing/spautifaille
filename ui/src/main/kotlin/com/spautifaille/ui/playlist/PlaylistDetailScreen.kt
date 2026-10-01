package com.spautifaille.ui.playlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.LoadingState
import com.spautifaille.ui.components.OfflineBanner
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.library.LibraryConfirmDialog
import com.spautifaille.ui.library.PlaylistCover
import com.spautifaille.ui.library.PlaylistNameDialog
import com.spautifaille.ui.library.displayName
import com.spautifaille.ui.library.totalDurationText
import com.spautifaille.ui.library.trackCountText
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
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
)

/**
 * Détail d'une playlist : locale, « Titres likés » ou « Téléchargés » (virtuelle, `Playlist.DOWNLOADED_ID`).
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
    val haptics = LocalAppHaptics.current

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
                PlaylistDetailEvent.TrackUnavailableOffline -> {
                    haptics.reject()
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(resources.getString(R.string.lib_track_unavailable_offline))
                    }
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
    val haptics = LocalAppHaptics.current
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetEntry by remember { mutableStateOf<PlaylistEntry?>(null) }
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val playlist = state.playlist

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(visible = showTitle, enter = fadeIn(), exit = fadeOut()) {
                        Text(playlist?.displayName().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptics.click()
                        actions.onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lib_back),
                        )
                    }
                },
                actions = {
                    if (playlist != null && !state.isSystem) {
                        Box {
                            IconButton(onClick = {
                                haptics.click()
                                menuOpen = true
                            }) {
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
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            OfflineBanner(visible = state.isOffline)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when {
                    state.isLoading -> LoadingState()
                    playlist == null -> EmptyState(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = stringResource(R.string.lib_playlist_not_found),
                        message = stringResource(R.string.lib_playlist_not_found_body),
                        actionLabel = stringResource(R.string.lib_back),
                        onAction = actions.onBack,
                    )
                    else -> PlaylistContent(
                        state = state,
                        playlist = playlist,
                        actions = actions,
                        listState = listState,
                        onTrackMore = { sheetEntry = it },
                    )
                }
            }
        }
    }

    val entryForSheet = sheetEntry
    if (entryForSheet != null) {
        TrackActionsSheet(
            track = entryForSheet.track,
            onDismiss = { sheetEntry = null },
            onGoToArtist = actions.onOpenArtist,
            removeLabel = if (state.isDownloadedPlaylist) R.string.lib_remove_download else R.string.lib_remove_from_playlist,
            onRemove = { actions.onRemove(entryForSheet) },
        )
    }
    if (showRename && playlist != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.lib_rename_playlist),
            confirmLabel = stringResource(R.string.lib_rename),
            initialName = playlist.name,
            onConfirm = {
                haptics.confirm()
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
                haptics.confirm()
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
    listState: LazyListState,
    onTrackMore: (PlaylistEntry) -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val reorderable = !state.isDownloadedPlaylist

    // Copie locale pendant le glissement : la liste réagit dans la même frame, sans attendre le StateFlow.
    var local by remember { mutableStateOf(state.entries) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = local.indexOfFirst { it.entryId == from.key }
        val toIndex = local.indexOfFirst { it.entryId == to.key }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            local = local.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            actions.onMove(fromIndex, toIndex)
            haptics.frequentTick()
        }
    }
    val dragging = reorderState.isAnyItemDragging
    LaunchedEffect(state.entries, dragging) { if (!dragging) local = state.entries }
    val entries = if (dragging) local else state.entries

    LazyColumn(
        state = listState,
        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
        contentPadding = PaddingValues(bottom = ListBottomPadding),
    ) {
        item(key = HEADER_KEY) {
            PlaylistHeader(
                playlist = playlist,
                entries = entries,
                state = state,
                actions = actions,
            )
        }
        if (entries.isEmpty()) {
            item(key = EMPTY_KEY) {
                Box(Modifier.fillMaxWidth().height(EmptyStateHeight)) {
                    if (state.isDownloadedPlaylist) {
                        EmptyState(
                            icon = Icons.Filled.DownloadDone,
                            title = stringResource(R.string.lib_downloads_empty_title),
                            message = stringResource(R.string.lib_downloads_empty_body),
                        )
                    } else {
                        EmptyState(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            title = stringResource(R.string.lib_playlist_empty_title),
                            message = stringResource(R.string.lib_playlist_empty_body),
                        )
                    }
                }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.entryId }) { index, entry ->
            ReorderableItem(reorderState, key = entry.entryId) { isDragging ->
                PlaylistEntryRow(
                    entry = entry,
                    isDragging = isDragging,
                    isAvailable = state.isAvailable(entry),
                    isDownloaded = entry.track.id in state.downloadedIds,
                    isCurrent = entry.track.id == state.currentTrackId,
                    isPlaying = state.isPlaying,
                    reorderable = reorderable,
                    removable = reorderable,
                    onClick = { actions.onPlayFrom(index) },
                    onMore = { onTrackMore(entry) },
                    onRemove = { actions.onRemove(entry) },
                    onDragStopped = {
                        haptics.tick()
                        actions.onDragEnd()
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: Playlist,
    entries: List<PlaylistEntry>,
    state: PlaylistDetailUiState,
    actions: PlaylistDetailActions,
) {
    val haptics = LocalAppHaptics.current
    val mosaicUrls = remember(entries) {
        entries.mapNotNull { it.track.thumbnailUrl }.distinct().take(MOSAIC_TILES)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        PlaylistCover(
            playlist = playlist,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .widthIn(max = CoverMaxSize)
                .aspectRatio(1f),
            shape = MaterialTheme.shapes.extraLarge,
            mosaicUrls = mosaicUrls,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = playlist.displayName(),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = if (entries.isNotEmpty()) {
                "${trackCountText(entries.size)} · ${totalDurationText(entries.sumOf { it.track.durationMs ?: 0L })}"
            } else {
                trackCountText(0)
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    haptics.click()
                    actions.onPlayAll(false)
                },
                enabled = state.canPlay,
                modifier = Modifier.weight(1f).height(ActionButtonHeight),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                Text(stringResource(R.string.lib_play), Modifier.padding(start = Spacing.s))
            }
            FilledTonalButton(
                onClick = {
                    haptics.click()
                    actions.onPlayAll(true)
                },
                enabled = state.canPlay,
                modifier = Modifier.weight(1f).height(ActionButtonHeight),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, Modifier.size(20.dp))
                Text(stringResource(R.string.lib_shuffle), Modifier.padding(start = Spacing.s))
            }
            if (!state.isDownloadedPlaylist && entries.isNotEmpty()) {
                FilledTonalIconButton(
                    onClick = {
                        haptics.click()
                        actions.onDownloadAll()
                    },
                    enabled = state.canDownload,
                    modifier = Modifier.size(ActionButtonHeight),
                ) {
                    if (state.isFullyDownloaded) {
                        Icon(Icons.Filled.DownloadDone, contentDescription = stringResource(R.string.lib_downloaded_all))
                    } else {
                        Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.lib_download_all))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderableCollectionItemScope.PlaylistEntryRow(
    entry: PlaylistEntry,
    isDragging: Boolean,
    isAvailable: Boolean,
    isDownloaded: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    reorderable: Boolean,
    removable: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onRemove: () -> Unit,
    onDragStopped: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            haptics.confirm()
            onRemove()
        }
    }
    val row: @Composable () -> Unit = {
        Surface(
            tonalElevation = if (isDragging) 6.dp else 0.dp,
            shadowElevation = if (isDragging) 6.dp else 0.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackListItem(
                    track = entry.track,
                    onClick = onClick,
                    onMoreClick = onMore,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (isAvailable) 1f else UnavailableAlpha),
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    isDownloaded = isDownloaded,
                )
                if (reorderable) {
                    IconButton(
                        modifier = Modifier.draggableHandle(onDragStopped = { onDragStopped() }),
                        onClick = {},
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.lib_reorder),
                        )
                    }
                }
            }
        }
    }
    if (removable) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = Spacing.l),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            },
        ) { row() }
    } else {
        row()
    }
}

private const val HEADER_KEY = "header"
private const val EMPTY_KEY = "empty"
private const val MOSAIC_TILES = 4
private const val UnavailableAlpha = 0.38f
private val CoverMaxSize = 260.dp
private val ActionButtonHeight = 52.dp
private val EmptyStateHeight = 280.dp

// region Previews

private fun previewEntries(count: Int) = (1..count).map {
    PlaylistEntry(
        entryId = it.toLong(),
        position = it - 1,
        track = Track("id$it", "Titre numéro $it", "Artiste $it", "https://youtube.com/@a$it", durationMs = 200_000L + it * 5_000L),
    )
}

private fun previewState(
    playlistId: Long = 5L,
    name: String = "Road trip",
    count: Int = 6,
    offline: Boolean = false,
    downloaded: Set<String> = setOf("id1", "id2", "id3"),
) = PlaylistDetailUiState(
    isLoading = false,
    playlist = Playlist(
        id = playlistId,
        name = name,
        trackCount = count,
        thumbnailUrl = null,
        isSystem = playlistId < 0 || playlistId == Playlist.LIKED_ID,
        createdAt = 0,
        updatedAt = 0,
    ),
    entries = previewEntries(count),
    downloadedIds = downloaded,
    isOffline = offline,
    currentTrackId = "id2",
    isPlaying = true,
)

@Composable
private fun PreviewHost(state: PlaylistDetailUiState) {
    SpautifailleTheme(dynamicColor = false) {
        PlaylistDetailScreen(state, PlaylistDetailActions(), remember { SnackbarHostState() })
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailPreview() = PreviewHost(previewState())

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailLikedPreview() =
    PreviewHost(previewState(playlistId = Playlist.LIKED_ID, name = "Titres likés"))

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailDownloadedPreview() = PreviewHost(
    previewState(
        playlistId = Playlist.DOWNLOADED_ID,
        name = "Téléchargés",
        count = 3,
        downloaded = setOf("id1", "id2", "id3"),
    ),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailOfflinePreview() = PreviewHost(previewState(offline = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailEmptyPreview() = PreviewHost(previewState(count = 0))

// endregion
