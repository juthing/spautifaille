package com.spautifaille.ui.youtube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.youtube.RemoteLibraryPlaylist
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.AppButtonDefaults
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.LoadingState
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

@Composable
fun YouTubePlaylistPickerScreenRoot(
    onBack: () -> Unit,
    onImported: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: YouTubePlaylistPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnImported by rememberUpdatedState(onImported)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is YouTubePlaylistPickerEvent.Imported -> currentOnImported(event.playlistIds)
            }
        }
    }
    YouTubePlaylistPickerScreen(
        state = state,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onToggleAll = viewModel::toggleAll,
        onImport = viewModel::import,
        onRetry = viewModel::load,
        modifier = modifier,
    )
}

/**
 * Playlists du compte YouTube avec cases à cocher (sélection multiple) ; celles déjà importées sont grisées.
 * Le bouton du bas importe la sélection comme playlists liées.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubePlaylistPickerScreen(
    state: YouTubePlaylistPickerUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val haptics = LocalAppHaptics.current
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.yt_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_action_back),
                        )
                    }
                },
                actions = {
                    if (state.selectableCount > 0) {
                        TextButton(onClick = {
                            haptics.click()
                            onToggleAll()
                        }) {
                            Text(
                                stringResource(
                                    if (state.allSelected) R.string.yt_picker_select_none else R.string.yt_picker_select_all,
                                ),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state.playlists.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                onImport()
                            },
                            enabled = state.canImport,
                            colors = AppButtonDefaults.filledColors(),
                            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                        ) {
                            if (state.isImporting) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else if (state.selectedIds.isEmpty()) {
                                Text(stringResource(R.string.yt_picker_import_none))
                            } else {
                                Text(
                                    pluralStringResource(
                                        R.plurals.yt_picker_import_count,
                                        state.selectedIds.size,
                                        state.selectedIds.size,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            when {
                state.isLoading -> LoadingState()
                state.error != null && state.playlists.isEmpty() ->
                    ErrorState(message = state.error.toMessage(), onRetry = onRetry)
                state.playlists.isEmpty() -> EmptyState(
                    title = stringResource(R.string.yt_picker_empty_title),
                    message = stringResource(R.string.yt_picker_empty_body),
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                )
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = ListBottomPadding),
                ) {
                    items(state.playlists, key = { it.id }) { playlist ->
                        PlaylistPickerRow(
                            playlist = playlist,
                            selected = playlist.id in state.selectedIds,
                            onToggle = { onToggle(playlist.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerRow(playlist: RemoteLibraryPlaylist, selected: Boolean, onToggle: () -> Unit) {
    val haptics = LocalAppHaptics.current
    val enabled = !playlist.isLinked
    ListItem(
        headlineContent = { Text(playlist.title, maxLines = 1) },
        supportingContent = {
            val parts = buildList {
                playlist.trackCount?.let { add(pluralStringResource(R.plurals.common_track_count, it, it)) }
                if (playlist.isLinked) add(stringResource(R.string.yt_picker_already_linked))
                else if (!playlist.isOwned) add(stringResource(R.string.yt_picker_read_only))
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), maxLines = 1)
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Checkbox(checked = selected || playlist.isLinked, onCheckedChange = null, enabled = enabled)
                Artwork(
                    url = playlist.thumbnailUrl,
                    modifier = Modifier.size(ArtworkSize.Avatar),
                    placeholderIcon = Icons.AutoMirrored.Filled.QueueMusic,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = {
                haptics.toggle(it)
                onToggle()
            },
        ),
    )
}

// region Previews

private val PreviewPlaylists = listOf(
    RemoteLibraryPlaylist("PL1", "Road trip", null, 42, isOwned = true, isLinked = false),
    RemoteLibraryPlaylist("PL2", "Soirée", null, 12, isOwned = true, isLinked = true),
    RemoteLibraryPlaylist("PL3", "Mix d'un ami", null, 100, isOwned = false, isLinked = false),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun YouTubePlaylistPickerPreview() {
    SpautifailleTheme(dynamicColor = false) {
        YouTubePlaylistPickerScreen(
            state = YouTubePlaylistPickerUiState(isLoading = false, playlists = PreviewPlaylists, selectedIds = setOf("PL1")),
            onBack = {},
            onToggle = {},
            onToggleAll = {},
            onImport = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun YouTubePlaylistPickerErrorPreview() {
    SpautifailleTheme(dynamicColor = false) {
        YouTubePlaylistPickerScreen(
            state = YouTubePlaylistPickerUiState(isLoading = false, error = AppError.Network),
            onBack = {},
            onToggle = {},
            onToggleAll = {},
            onImport = {},
            onRetry = {},
        )
    }
}

// endregion
