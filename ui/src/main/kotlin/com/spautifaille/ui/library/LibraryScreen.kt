package com.spautifaille.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Playlist
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.LoadingState
import com.spautifaille.ui.components.OfflineBanner
import com.spautifaille.ui.components.hideSheet
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

/** Actions de l'écran Bibliothèque (navigation + intentions utilisateur). Valeurs par défaut vides pour les previews. */
@Immutable
data class LibraryActions(
    val onOpenPlaylist: (Long) -> Unit = {},
    val onOpenImport: () -> Unit = {},
    val onCreatePlaylist: (String) -> Unit = {},
    val onRenamePlaylist: (id: Long, name: String) -> Unit = { _, _ -> },
    val onDeletePlaylist: (Long) -> Unit = {},
    val onPlayPlaylist: (id: Long, shuffle: Boolean) -> Unit = { _, _ -> },
    val onDownloadPlaylist: (Long) -> Unit = {},
)

@Composable
fun LibraryRoute(
    onOpenPlaylist: (Long) -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenPlaylist, onOpenImport) {
        LibraryActions(
            onOpenPlaylist = onOpenPlaylist,
            onOpenImport = onOpenImport,
            onCreatePlaylist = { viewModel.createPlaylist(it) },
            onRenamePlaylist = { id, name -> viewModel.renamePlaylist(id, name) },
            onDeletePlaylist = { viewModel.deletePlaylist(it) },
            onPlayPlaylist = viewModel::playPlaylist,
            onDownloadPlaylist = viewModel::downloadPlaylist,
        )
    }
    LibraryScreen(state = state, actions = actions, modifier = modifier)
}

/**
 * Bibliothèque : uniquement des playlists (« Titres likés » et « Téléchargés » épinglées en tête).
 * Création via le bouton rond « + » (feuille : playlist vide ou import), menu d'une playlist par appui long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    var showNameDialog by rememberSaveable { mutableStateOf(false) }
    var menuPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.lib_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptics.click()
                    showNewSheet = true
                },
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.lib_create_playlist_fab))
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            OfflineBanner(visible = state.isOffline)
            if (state.isLoading) {
                LoadingState()
            } else {
                PlaylistList(
                    playlists = state.playlists,
                    hasUserPlaylists = state.hasUserPlaylists,
                    onOpen = { actions.onOpenPlaylist(it.id) },
                    onOpenMenu = { menuPlaylistId = it.id },
                )
            }
        }
    }

    if (showNewSheet) {
        NewPlaylistSheet(
            onDismiss = { showNewSheet = false },
            onCreateEmpty = { showNameDialog = true },
            onImport = actions.onOpenImport,
        )
    }
    if (showNameDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.lib_new_playlist),
            confirmLabel = stringResource(R.string.lib_create),
            initialName = "",
            onConfirm = {
                haptics.confirm()
                actions.onCreatePlaylist(it)
                showNameDialog = false
            },
            onDismiss = { showNameDialog = false },
        )
    }

    val menuPlaylist = state.playlists.firstOrNull { it.id == menuPlaylistId }
    if (menuPlaylist != null) {
        PlaylistActionsSheet(
            playlist = menuPlaylist,
            isOffline = state.isOffline,
            onDismiss = { menuPlaylistId = null },
            onPlay = { shuffle -> actions.onPlayPlaylist(menuPlaylist.id, shuffle) },
            onDownload = { actions.onDownloadPlaylist(menuPlaylist.id) },
            onRename = { renameTargetId = menuPlaylist.id },
            onDelete = { deleteTargetId = menuPlaylist.id },
        )
    }
    val renameTarget = state.playlists.firstOrNull { it.id == renameTargetId && !it.isPinned }
    if (renameTarget != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.lib_rename_playlist),
            confirmLabel = stringResource(R.string.lib_rename),
            initialName = renameTarget.name,
            onConfirm = {
                haptics.confirm()
                actions.onRenamePlaylist(renameTarget.id, it)
                renameTargetId = null
            },
            onDismiss = { renameTargetId = null },
        )
    }
    val deleteTarget = state.playlists.firstOrNull { it.id == deleteTargetId && !it.isPinned }
    if (deleteTarget != null) {
        LibraryConfirmDialog(
            title = stringResource(R.string.lib_delete_playlist_title),
            text = stringResource(R.string.lib_delete_playlist_message, deleteTarget.name),
            confirmLabel = stringResource(R.string.lib_delete),
            onConfirm = {
                haptics.confirm()
                actions.onDeletePlaylist(deleteTarget.id)
                deleteTargetId = null
            },
            onDismiss = { deleteTargetId = null },
        )
    }
}

// region Liste

/** Espace sous la liste : le FAB (56 dp + marges) ne doit pas masquer le dernier élément. */
private val FabClearance = 56.dp + Spacing.m * 2 + ListBottomPadding
private val WideLayoutThreshold = 600.dp
private val RowCoverSize = 72.dp

@Composable
private fun PlaylistList(
    playlists: List<Playlist>,
    hasUserPlaylists: Boolean,
    onOpen: (Playlist) -> Unit,
    onOpenMenu: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= WideLayoutThreshold) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ScreenHorizontalPadding,
                    end = ScreenHorizontalPadding,
                    top = Spacing.s,
                    bottom = FabClearance,
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = {
                            haptics.click()
                            onOpen(playlist)
                        },
                        onLongClick = {
                            haptics.longPress()
                            onOpenMenu(playlist)
                        },
                    )
                }
                if (!hasUserPlaylists) {
                    item(key = EMPTY_KEY, span = { GridItemSpan(maxLineSpan) }) { NoPlaylistsHint() }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.s,
                    end = Spacing.s,
                    top = Spacing.xs,
                    bottom = FabClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = {
                            haptics.click()
                            onOpen(playlist)
                        },
                        onLongClick = {
                            haptics.longPress()
                            onOpenMenu(playlist)
                        },
                        onMoreClick = {
                            haptics.click()
                            onOpenMenu(playlist)
                        },
                    )
                }
                if (!hasUserPlaylists) {
                    item(key = EMPTY_KEY) { NoPlaylistsHint() }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            PlaylistCover(playlist, Modifier.size(RowCoverSize), MaterialTheme.shapes.large)
        },
        headlineContent = {
            Text(
                text = playlist.displayName(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(text = trackCountText(playlist.trackCount), maxLines = 1)
        },
        trailingContent = {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.lib_more_options))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        PlaylistCover(playlist, Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.large)
        Text(
            text = playlist.displayName(),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Text(
            text = trackCountText(playlist.trackCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Invite affichée sous les playlists épinglées tant que l'utilisateur n'en a créé aucune. */
@Composable
private fun NoPlaylistsHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = stringResource(R.string.lib_empty_playlists_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.lib_empty_playlists_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val EMPTY_KEY = "empty"

// endregion

// region Création

/** Feuille « Nouvelle playlist » : deux grandes cartes, playlist vide ou import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPlaylistSheet(
    onDismiss: () -> Unit,
    onCreateEmpty: () -> Unit,
    onImport: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = ScreenHorizontalPadding)
                .padding(bottom = Spacing.l)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                text = stringResource(R.string.lib_new_playlist),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
            NewPlaylistOption(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = stringResource(R.string.lib_new_playlist_empty_title),
                description = stringResource(R.string.lib_new_playlist_empty_body),
                onClick = {
                    scope.hideSheet(sheetState, onDismiss)
                    onCreateEmpty()
                },
            )
            NewPlaylistOption(
                icon = Icons.Filled.UploadFile,
                title = stringResource(R.string.lib_new_playlist_import_title),
                description = stringResource(R.string.lib_new_playlist_import_body),
                onClick = {
                    scope.hideSheet(sheetState, onDismiss)
                    onImport()
                },
            )
        }
    }
}

@Composable
private fun NewPlaylistOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    Card(
        onClick = {
            haptics.click()
            onClick()
        },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// endregion

// region Menu d'une playlist

/** Menu (appui long) : lire, aléatoire, télécharger, renommer, supprimer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistActionsSheet(
    playlist: Playlist,
    isOffline: Boolean,
    onDismiss: () -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val hasTracks = playlist.trackCount > 0
    val canDownload = hasTracks && !isOffline && playlist.id != Playlist.DOWNLOADED_ID

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    PlaylistCover(playlist, Modifier.size(56.dp), MaterialTheme.shapes.medium)
                },
                headlineContent = {
                    Text(
                        text = playlist.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = { Text(trackCountText(playlist.trackCount)) },
            )
            HorizontalDivider()
            if (hasTracks) {
                SheetAction(Icons.Filled.PlayArrow, stringResource(R.string.lib_play)) {
                    onPlay(false)
                    scope.hideSheet(sheetState, onDismiss)
                }
                SheetAction(Icons.Filled.Shuffle, stringResource(R.string.lib_shuffle)) {
                    onPlay(true)
                    scope.hideSheet(sheetState, onDismiss)
                }
            }
            if (canDownload) {
                SheetAction(Icons.Filled.Download, stringResource(R.string.lib_action_download)) {
                    onDownload()
                    scope.hideSheet(sheetState, onDismiss)
                }
            }
            if (!playlist.isPinned) {
                SheetAction(Icons.Filled.Edit, stringResource(R.string.lib_rename)) {
                    scope.hideSheet(sheetState, onDismiss)
                    onRename()
                }
                SheetAction(Icons.Filled.Delete, stringResource(R.string.lib_delete), destructive = true) {
                    scope.hideSheet(sheetState, onDismiss)
                    onDelete()
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable {
            haptics.click()
            onClick()
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = tint,
            leadingIconColor = if (destructive) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}

// endregion

// region Previews

internal fun previewLibraryState(offline: Boolean = false, withUserPlaylists: Boolean = true): LibraryUiState {
    val stored = buildList {
        add(Playlist(Playlist.LIKED_ID, "Titres likés", 42, null, true, 0, 0))
        if (withUserPlaylists) {
            add(Playlist(2, "Road trip", 18, null, false, 0, 0))
            add(Playlist(3, "Concentration", 64, null, false, 0, 0))
        }
    }
    return LibraryUiState(
        isLoading = false,
        playlists = orderLibraryPlaylists(stored, downloadedCount = 12, isOffline = offline),
        isOffline = offline,
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LibraryScreenPreview() {
    SpautifailleTheme(dynamicColor = false) { LibraryScreen(state = previewLibraryState(), actions = LibraryActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LibraryScreenOfflinePreview() {
    SpautifailleTheme(dynamicColor = false) {
        LibraryScreen(state = previewLibraryState(offline = true), actions = LibraryActions())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LibraryScreenEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        LibraryScreen(state = previewLibraryState(withUserPlaylists = false), actions = LibraryActions())
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 640)
@Composable
private fun LibraryScreenWidePreview() {
    SpautifailleTheme(dynamicColor = false) { LibraryScreen(state = previewLibraryState(), actions = LibraryActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 240)
@Composable
private fun NewPlaylistOptionPreview() {
    SpautifailleTheme(dynamicColor = false) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            NewPlaylistOption(
                Icons.AutoMirrored.Filled.QueueMusic,
                "Playlist vide",
                "Crée-la et ajoute tes titres toi-même",
                onClick = {},
            )
            NewPlaylistOption(
                Icons.Filled.UploadFile,
                "Importer",
                "Depuis Spotify, un fichier ou un lien YouTube",
                onClick = {},
            )
        }
    }
}

// endregion
