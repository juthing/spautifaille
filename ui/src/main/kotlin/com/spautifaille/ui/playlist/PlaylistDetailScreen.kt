package com.spautifaille.ui.playlist

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import com.spautifaille.ui.components.AddToPlaylistSheet
import com.spautifaille.ui.components.AppButtonDefaults
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
import com.spautifaille.ui.youtube.PlaylistYouTubeViewModel
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
    /** Bouton principal : lecture / pause / reprise de CETTE playlist. */
    val onPlayButton: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onDownloadAll: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    /** Playlist liée à YouTube : coupe le lien (la playlist reste intacte des deux côtés). */
    val onUnlinkFromYouTube: () -> Unit = {},
    /** Playlist locale non liée : la crée sur le compte YouTube puis la lie. */
    val onPublishToYouTube: () -> Unit = {},
    val onDragStart: (entryId: Long) -> Unit = {},
    val onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onDragEnd: () -> Unit = {},
    val onRemove: (PlaylistEntry) -> Unit = {},
    // Sélection multiple
    val onToggleSelect: (entryId: Long) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onPlaySelection: () -> Unit = {},
    val onPlaySelectionNext: () -> Unit = {},
    val onAddSelectionToQueue: () -> Unit = {},
    val onDownloadSelection: () -> Unit = {},
    val onLikeSelection: () -> Unit = {},
    val onRemoveSelection: () -> Unit = {},
)

/**
 * Détail d'une playlist : locale, « Titres likés » ou « Téléchargés » (virtuelle, `Playlist.DOWNLOADED_ID`).
 * Argument de navigation lu par le ViewModel : `SavedStateHandle["id"]` (Long).
 *
 * Gestes sur une ligne : toucher = lire (ou basculer la sélection en mode sélection) ; ⋮ = menu du titre ;
 * appui long puis glisser = réorganiser ; appui long relâché sans déplacement = entrer en mode sélection.
 */
@Composable
fun PlaylistDetailRoute(
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
    youTubeViewModel: PlaylistYouTubeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val canPublishToYouTube by youTubeViewModel.canPublish.collectAsStateWithLifecycle()
    val youTubeBusy by youTubeViewModel.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val haptics = LocalAppHaptics.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PlaylistDetailEvent.TracksRemoved -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val message = if (event.items.size == 1) {
                        resources.getString(R.string.lib_track_removed, event.items.first().track.title)
                    } else {
                        resources.getQuantityString(R.plurals.lib_tracks_removed, event.items.size, event.items.size)
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = resources.getString(R.string.lib_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove(event.items)
                }
                is PlaylistDetailEvent.DownloadsQueued -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        resources.getQuantityString(R.plurals.lib_downloads_queued, event.count, event.count),
                    )
                }
                is PlaylistDetailEvent.TracksLiked -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val plural = if (event.liked) R.plurals.lib_tracks_liked else R.plurals.lib_tracks_unliked
                    snackbarHostState.showSnackbar(resources.getQuantityString(plural, event.count, event.count))
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

    val actions = remember(viewModel, youTubeViewModel, onBack, onOpenArtist) {
        PlaylistDetailActions(
            onBack = onBack,
            onOpenArtist = onOpenArtist,
            onPlayFrom = viewModel::playFrom,
            onPlayButton = viewModel::onPlayButton,
            onShuffle = { viewModel.playAll(shuffle = true) },
            onDownloadAll = viewModel::downloadAll,
            onRename = { viewModel.rename(it) },
            onDelete = { viewModel.delete() },
            onUnlinkFromYouTube = youTubeViewModel::unlink,
            onPublishToYouTube = youTubeViewModel::publish,
            onDragStart = viewModel::onDragStart,
            onMove = viewModel::onMove,
            onDragEnd = viewModel::onDragEnd,
            onRemove = viewModel::removeEntry,
            onToggleSelect = viewModel::toggleSelection,
            onSelectAll = viewModel::toggleSelectAll,
            onClearSelection = viewModel::clearSelection,
            onPlaySelection = viewModel::playSelection,
            onPlaySelectionNext = viewModel::playSelectionNext,
            onAddSelectionToQueue = viewModel::addSelectionToQueue,
            onDownloadSelection = viewModel::downloadSelection,
            onLikeSelection = viewModel::likeSelection,
            onRemoveSelection = viewModel::removeSelection,
        )
    }
    PlaylistDetailScreen(
        state = state,
        actions = actions,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        canPublishToYouTube = canPublishToYouTube,
        youTubeBusy = youTubeBusy,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailUiState,
    actions: PlaylistDetailActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    /** Un compte YouTube est connecté : « Publier sur YouTube » est proposé pour une playlist non liée. */
    canPublishToYouTube: Boolean = false,
    youTubeBusy: Boolean = false,
) {
    val haptics = LocalAppHaptics.current
    var showUnlink by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    // Titres figés à l'ouverture de la feuille : la sélection se vide dès l'ajout, la feuille finit son animation.
    var addToPlaylistTracks by remember { mutableStateOf<List<Track>?>(null) }
    var showRemoveDownloads by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetEntry by remember { mutableStateOf<PlaylistEntry?>(null) }
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val playlist = state.playlist

    // Le retour système quitte d'abord la sélection.
    BackHandler(enabled = state.isSelecting) { actions.onClearSelection() }

    Scaffold(
        modifier = modifier,
        topBar = {
            Crossfade(targetState = state.isSelecting, label = "playlistTopBar") { selecting ->
                if (selecting) {
                    SelectionTopBar(
                        state = state,
                        actions = actions,
                        onAddToPlaylist = { addToPlaylistTracks = state.selectedTracks },
                        onRemove = {
                            if (state.isDownloadedPlaylist) showRemoveDownloads = true else actions.onRemoveSelection()
                        },
                    )
                } else {
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
                                    contentDescription = stringResource(R.string.common_action_back),
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
                                        if (playlist.isLinkedToYouTube) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.yt_unlink)) },
                                                leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                                                onClick = {
                                                    menuOpen = false
                                                    showUnlink = true
                                                },
                                            )
                                        } else if (canPublishToYouTube) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.yt_publish)) },
                                                leadingIcon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
                                                enabled = !youTubeBusy,
                                                onClick = {
                                                    menuOpen = false
                                                    actions.onPublishToYouTube()
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.lib_rename)) },
                                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                showRename = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.common_action_delete)) },
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
                }
            }
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
                        actionLabel = stringResource(R.string.common_action_back),
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
            isPlayable = state.isAvailable(entryForSheet),
            // « Téléchargés » : la feuille propose déjà « Supprimer le téléchargement » (avec confirmation).
            onRemove = if (state.isDownloadedPlaylist) null else ({ actions.onRemove(entryForSheet) }),
        )
    }
    val tracksToAdd = addToPlaylistTracks
    if (tracksToAdd != null) {
        AddToPlaylistSheet(
            tracks = tracksToAdd,
            onDismiss = { addToPlaylistTracks = null },
            // La sélection ne se vide qu'une fois les titres réellement ajoutés (pas si la feuille est fermée).
            onAdded = actions.onClearSelection,
        )
    }
    if (showRemoveDownloads) {
        LibraryConfirmDialog(
            title = stringResource(R.string.lib_delete_downloads_title),
            text = pluralStringResource(
                R.plurals.lib_delete_downloads_message,
                state.selectedEntryIds.size,
                state.selectedEntryIds.size,
            ),
            confirmLabel = stringResource(R.string.common_action_delete),
            onConfirm = {
                haptics.confirm()
                actions.onRemoveSelection()
                showRemoveDownloads = false
            },
            onDismiss = { showRemoveDownloads = false },
        )
    }
    if (showUnlink && playlist != null) {
        LibraryConfirmDialog(
            title = stringResource(R.string.yt_unlink_title),
            text = stringResource(R.string.yt_unlink_message),
            confirmLabel = stringResource(R.string.yt_unlink),
            onConfirm = {
                haptics.confirm()
                actions.onUnlinkFromYouTube()
                showUnlink = false
            },
            onDismiss = { showUnlink = false },
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
            confirmLabel = stringResource(R.string.common_action_delete),
            onConfirm = {
                haptics.confirm()
                actions.onDelete()
                showDelete = false
            },
            onDismiss = { showDelete = false },
        )
    }
}

/**
 * Barre contextuelle du mode sélection : fermer, « N sélectionnés », tout sélectionner, lire, retirer, et un menu
 * pour le reste (lire ensuite, ajouter à la file / à une playlist, télécharger, aimer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    state: PlaylistDetailUiState,
    actions: PlaylistDetailActions,
    onAddToPlaylist: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    var menuOpen by remember { mutableStateOf(false) }
    val count = state.selectedEntryIds.size
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        title = {
            Text(
                pluralStringResource(R.plurals.lib_selection_count, count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = {
                haptics.click()
                actions.onClearSelection()
            }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.lib_selection_close))
            }
        },
        actions = {
            IconButton(onClick = {
                haptics.click()
                actions.onSelectAll()
            }) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = stringResource(if (state.allSelected) R.string.lib_deselect_all else R.string.lib_select_all),
                )
            }
            IconButton(onClick = actions.onPlaySelection, enabled = state.canPlaySelection) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.common_action_play))
            }
            IconButton(onClick = {
                haptics.click()
                onRemove()
            }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(
                        if (state.isDownloadedPlaylist) R.string.common_action_delete_download else R.string.lib_remove_from_playlist,
                    ),
                )
            }
            Box {
                IconButton(onClick = {
                    haptics.click()
                    menuOpen = true
                }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.lib_selection_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_action_play_next)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                        enabled = state.canPlaySelection,
                        onClick = {
                            menuOpen = false
                            actions.onPlaySelectionNext()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_action_add_to_queue)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        enabled = state.canPlaySelection,
                        onClick = {
                            menuOpen = false
                            actions.onAddSelectionToQueue()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_action_add_to_playlist)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        },
                    )
                    if (!state.isDownloadedPlaylist) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_action_download)) },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            enabled = state.canDownloadSelection,
                            onClick = {
                                menuOpen = false
                                actions.onDownloadSelection()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (state.allSelectedLiked) R.string.common_action_unlike else R.string.common_action_like))
                        },
                        leadingIcon = {
                            Icon(
                                if (state.allSelectedLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            actions.onLikeSelection()
                        },
                    )
                }
            }
        },
    )
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
            ReorderableItem(reorderState, key = entry.entryId, enabled = reorderable && !state.isSelecting) { isDragging ->
                PlaylistEntryRow(
                    entry = entry,
                    isDragging = isDragging,
                    isAvailable = state.isAvailable(entry),
                    isDownloaded = entry.track.id in state.downloadedIds,
                    isCurrent = entry.track.id == state.currentTrackId,
                    isPlaying = state.isPlaying,
                    reorderable = reorderable,
                    selectionMode = state.isSelecting,
                    isSelected = entry.entryId in state.selectedEntryIds,
                    canMoveUp = index > 0,
                    canMoveDown = index < entries.lastIndex,
                    onClick = { actions.onPlayFrom(index) },
                    onToggleSelect = { actions.onToggleSelect(entry.entryId) },
                    onMore = { onTrackMore(entry) },
                    onDragStarted = { actions.onDragStart(entry.entryId) },
                    onDragStopped = {
                        haptics.tick()
                        actions.onDragEnd()
                    },
                    onMoveBy = { delta ->
                        actions.onMove(index, index + delta)
                        actions.onDragEnd()
                    },
                )
            }
        }
    }
}

/** Badge « Synchronisée avec YouTube » des playlists liées. */
@Composable
private fun YouTubeLinkedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.yt_linked_badge), style = MaterialTheme.typography.labelMedium)
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
            if (playlist.isLinkedToYouTube) YouTubeLinkedBadge()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lecture / Pause / Reprendre : « Pause » et « Reprendre » concernent la file en cours si elle vient de
            // cette playlist (sinon « Lecture » relance la playlist depuis le début).
            val playAction = state.playAction
            Button(
                onClick = {
                    haptics.click()
                    actions.onPlayButton()
                },
                enabled = state.canPlay || playAction != PlaylistPlayAction.PLAY,
                colors = AppButtonDefaults.filledColors(),
                modifier = Modifier.weight(1f).height(ActionButtonHeight),
            ) {
                Icon(
                    if (playAction == PlaylistPlayAction.PAUSE) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(
                        when (playAction) {
                            PlaylistPlayAction.PLAY -> R.string.lib_play
                            PlaylistPlayAction.PAUSE -> R.string.common_action_pause
                            PlaylistPlayAction.RESUME -> R.string.lib_resume
                        },
                    ),
                    Modifier.padding(start = Spacing.s),
                )
            }
            FilledTonalButton(
                onClick = {
                    haptics.click()
                    actions.onShuffle()
                },
                enabled = state.canPlay,
                colors = AppButtonDefaults.tonalColors(),
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
                    colors = AppButtonDefaults.tonalIconColors(),
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

/**
 * Ligne de la playlist. Gestes :
 * - toucher : lire (ou basculer la sélection en mode sélection) ;
 * - ⋮ : menu du titre ;
 * - appui long puis glisser (playlists réordonnables) : réorganiser ; relâché sans déplacement : sélectionner ;
 * - « Téléchargés » (non réordonnable) : appui long = sélectionner.
 * Pas de balayage pour retirer : trop facile à déclencher en faisant défiler la liste (retrait via ⋮ ou sélection,
 * avec annulation).
 */
@Composable
private fun ReorderableCollectionItemScope.PlaylistEntryRow(
    entry: PlaylistEntry,
    isDragging: Boolean,
    isAvailable: Boolean,
    isDownloaded: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    reorderable: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onMore: () -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    onMoveBy: (delta: Int) -> Unit,
) {
    val haptics = LocalAppHaptics.current

    // La poignée d'appui long ne consomme pas le « relâcher » : sans garde, lever le doigt après un appui long sans
    // mouvement déclencherait aussi le clic de la ligne (donc la lecture du titre).
    var longPressActive by remember { mutableStateOf(false) }
    var suppressClickUntil by remember { mutableLongStateOf(0L) }
    val dragEnabled = reorderable && !selectionMode

    val moveUpLabel = stringResource(R.string.lib_move_up)
    val moveDownLabel = stringResource(R.string.lib_move_down)
    val selectLabel = stringResource(R.string.lib_select)

    val gestureModifier = Modifier
        .semantics {
            val custom = mutableListOf<CustomAccessibilityAction>()
            if (!selectionMode) custom += CustomAccessibilityAction(selectLabel) { onToggleSelect(); true }
            if (dragEnabled && canMoveUp) custom += CustomAccessibilityAction(moveUpLabel) { onMoveBy(-1); true }
            if (dragEnabled && canMoveDown) custom += CustomAccessibilityAction(moveDownLabel) { onMoveBy(1); true }
            customActions = custom
        }
        .longPressDraggableHandle(
            enabled = dragEnabled,
            onDragStarted = {
                longPressActive = true
                haptics.longPress()
                onDragStarted()
            },
            onDragStopped = {
                longPressActive = false
                suppressClickUntil = SystemClock.uptimeMillis() + CLICK_SUPPRESSION_MS
                onDragStopped()
            },
        )

    Surface(
        tonalElevation = if (isDragging) 6.dp else 0.dp,
        shadowElevation = if (isDragging) 6.dp else 0.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        TrackListItem(
            track = entry.track,
            onClick = {
                if (longPressActive || SystemClock.uptimeMillis() < suppressClickUntil) return@TrackListItem
                if (selectionMode) onToggleSelect() else onClick()
            },
            onMoreClick = onMore,
            modifier = Modifier
                .padding(horizontal = Spacing.s)
                .then(gestureModifier),
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            isDownloaded = isDownloaded,
            isAvailable = isAvailable,
            selectionMode = selectionMode,
            isSelected = isSelected,
            // Réordonnable : l'appui long appartient à la poignée. Sinon (« Téléchargés ») : sélection directe.
            onLongClick = if (!reorderable && !selectionMode) onToggleSelect else null,
            // Titre indisponible hors ligne : le refus (reject) tient lieu de retour haptique.
            clickFeedback = isAvailable || selectionMode,
        )
    }
}

private const val HEADER_KEY = "header"
private const val EMPTY_KEY = "empty"
private const val MOSAIC_TILES = 4
private const val CLICK_SUPPRESSION_MS = 350L
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
    selected: Set<Long> = emptySet(),
    thisQueue: Boolean = true,
    playing: Boolean = true,
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
    playableOfflineIds = downloaded + "id4",
    isOffline = offline,
    currentTrackId = "id2",
    isPlaying = playing,
    isThisPlaylistQueue = thisQueue,
    selectedEntryIds = selected,
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
private fun PlaylistDetailPausedPreview() = PreviewHost(previewState(playing = false))

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailOtherQueuePreview() = PreviewHost(previewState(thisQueue = false))

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PlaylistDetailSelectionPreview() = PreviewHost(previewState(selected = setOf(1L, 3L)))

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
private fun PlaylistDetailEmptyPreview() = PreviewHost(previewState(count = 0, thisQueue = false))

// endregion
