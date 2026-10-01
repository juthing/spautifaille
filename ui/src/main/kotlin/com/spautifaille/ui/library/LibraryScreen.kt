package com.spautifaille.ui.library

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import kotlinx.coroutines.launch

private enum class LibraryTab(@StringRes val label: Int) {
    Playlists(R.string.lib_tab_playlists),
    Downloads(R.string.lib_tab_downloads),
    History(R.string.lib_tab_history),
    Artists(R.string.lib_tab_artists),
}

/** Actions de l'écran Bibliothèque (navigation + intentions utilisateur). Valeurs par défaut vides pour les previews. */
@Immutable
data class LibraryActions(
    val onOpenPlaylist: (Long) -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onOpenImport: () -> Unit = {},
    val onOpenArtist: (String) -> Unit = {},
    val onCreatePlaylist: (String) -> Unit = {},
    val onRenamePlaylist: (id: Long, name: String) -> Unit = { _, _ -> },
    val onDeletePlaylist: (Long) -> Unit = {},
    val onPlayPlaylist: (id: Long, shuffle: Boolean) -> Unit = { _, _ -> },
    val onPlayDownloads: (startIndex: Int, shuffle: Boolean) -> Unit = { _, _ -> },
    val onPlayHistory: (Int) -> Unit = {},
    val onRemoveHistoryEntry: (Long) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onUnsubscribe: (String) -> Unit = {},
)

@Composable
fun LibraryRoute(
    onOpenPlaylist: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenPlaylist, onOpenDownloads, onOpenImport, onOpenArtist) {
        LibraryActions(
            onOpenPlaylist = onOpenPlaylist,
            onOpenDownloads = onOpenDownloads,
            onOpenImport = onOpenImport,
            onOpenArtist = onOpenArtist,
            onCreatePlaylist = { viewModel.createPlaylist(it) },
            onRenamePlaylist = { id, name -> viewModel.renamePlaylist(id, name) },
            onDeletePlaylist = { viewModel.deletePlaylist(it) },
            onPlayPlaylist = viewModel::playPlaylist,
            onPlayDownloads = viewModel::playDownloads,
            onPlayHistory = viewModel::playHistory,
            onRemoveHistoryEntry = viewModel::removeHistoryEntry,
            onClearHistory = viewModel::clearHistory,
            onUnsubscribe = viewModel::unsubscribe,
        )
    }
    LibraryScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val tabs = LibraryTab.entries
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.lib_title)) },
                    actions = {
                        IconButton(onClick = actions.onOpenImport) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.lib_action_import),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    text = stringResource(tab.label),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = pagerState.currentPage == LibraryTab.Playlists.ordinal,
                enter = scaleIn(),
                exit = scaleOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.lib_new_playlist)) },
                )
            }
        },
    ) { innerPadding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                key = { it },
            ) { page ->
                when (tabs[page]) {
                    LibraryTab.Playlists -> LibraryPlaylistsTab(
                        playlists = state.playlists,
                        onOpenPlaylist = actions.onOpenPlaylist,
                        onPlay = { id, shuffle -> actions.onPlayPlaylist(id, shuffle) },
                        onRename = { renameTargetId = it.id },
                        onDelete = { deleteTargetId = it.id },
                    )
                    LibraryTab.Downloads -> LibraryDownloadsTab(
                        downloads = state.downloads,
                        storage = state.storage,
                        onManage = actions.onOpenDownloads,
                        onPlay = actions.onPlayDownloads,
                    )
                    LibraryTab.History -> LibraryHistoryTab(
                        history = state.history,
                        onPlay = actions.onPlayHistory,
                        onRemove = actions.onRemoveHistoryEntry,
                        onClear = { showClearHistoryDialog = true },
                    )
                    LibraryTab.Artists -> LibraryArtistsTab(
                        artists = state.subscriptions,
                        onOpenArtist = actions.onOpenArtist,
                        onUnsubscribe = actions.onUnsubscribe,
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.lib_new_playlist),
            confirmLabel = stringResource(R.string.lib_create),
            initialName = "",
            onConfirm = {
                actions.onCreatePlaylist(it)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    val renameTarget = state.playlists.firstOrNull { it.id == renameTargetId && !it.isSystem }
    if (renameTarget != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.lib_rename_playlist),
            confirmLabel = stringResource(R.string.lib_rename),
            initialName = renameTarget.name,
            onConfirm = {
                actions.onRenamePlaylist(renameTarget.id, it)
                renameTargetId = null
            },
            onDismiss = { renameTargetId = null },
        )
    }
    val deleteTarget = state.playlists.firstOrNull { it.id == deleteTargetId && !it.isSystem }
    if (deleteTarget != null) {
        LibraryConfirmDialog(
            title = stringResource(R.string.lib_delete_playlist_title),
            text = stringResource(R.string.lib_delete_playlist_message, deleteTarget.name),
            confirmLabel = stringResource(R.string.lib_delete),
            onConfirm = {
                actions.onDeletePlaylist(deleteTarget.id)
                deleteTargetId = null
            },
            onDismiss = { deleteTargetId = null },
        )
    }
    if (showClearHistoryDialog) {
        LibraryConfirmDialog(
            title = stringResource(R.string.lib_clear_history),
            text = stringResource(R.string.lib_clear_history_message),
            confirmLabel = stringResource(R.string.lib_clear),
            onConfirm = {
                actions.onClearHistory()
                showClearHistoryDialog = false
            },
            onDismiss = { showClearHistoryDialog = false },
        )
    }
}

// region Previews

private fun previewTrack(index: Int) = Track(
    id = "video$index",
    title = "Titre numéro $index",
    artist = "Artiste $index",
    durationMs = 180_000L + index * 7_000L,
)

internal fun previewLibraryState(): LibraryUiState {
    val now = System.currentTimeMillis()
    return LibraryUiState(
        isLoading = false,
        playlists = listOf(
            Playlist(Playlist.LIKED_ID, "Titres likés", 42, null, true, now, now),
            Playlist(2, "Road trip", 18, null, false, now, now),
            Playlist(3, "Concentration", 64, null, false, now, now),
        ),
        downloads = (1..4).map {
            Download(previewTrack(it), DownloadState.COMPLETED, 1f, 4_000_000, 4_000_000, "/x", null, now)
        },
        storage = StorageUsage(downloadsBytes = 16_000_000, cacheBytes = 0, downloadCount = 4),
        history = (1..6).map { HistoryEntry(it.toLong(), previewTrack(it), now - it * 3_600_000L * 9) },
        subscriptions = listOf(Artist("https://youtube.com/@a", "Artiste A", null, 1_250_000)),
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LibraryScreenPreview() {
    MaterialTheme { LibraryScreen(state = previewLibraryState(), actions = LibraryActions()) }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 640)
@Composable
private fun LibraryScreenWidePreview() {
    MaterialTheme { LibraryScreen(state = previewLibraryState(), actions = LibraryActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LibraryScreenEmptyPreview() {
    MaterialTheme {
        LibraryScreen(
            state = LibraryUiState(
                isLoading = false,
                playlists = listOf(Playlist(Playlist.LIKED_ID, "Titres likés", 0, null, true, 0, 0)),
            ),
            actions = LibraryActions(),
        )
    }
}

// endregion
