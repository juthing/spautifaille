package com.spautifaille.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.theme.ArtworkSize

/** Feuille « Ajouter à une playlist » : playlists locales + création d'une nouvelle playlist. */
@Composable
fun AddToPlaylistSheet(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    viewModel: TrackActionsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    AddToPlaylistSheetContent(
        playlists = playlists,
        onDismiss = onDismiss,
        onPlaylistSelected = { viewModel.addToPlaylist(it, tracks) },
        onCreatePlaylist = { name -> viewModel.createPlaylist(name, tracks) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheetContent(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlaylistSelected: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    val haptics = LocalAppHaptics.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.common_add_to_playlist_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.navigationBarsPadding()) {
            item(key = "new") {
                ListItem(
                    modifier = Modifier.clickable {
                        haptics.click()
                        showCreateDialog = true
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(ArtworkSize.Row),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    },
                    headlineContent = {
                        Text(stringResource(R.string.common_action_new_playlist), color = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            items(playlists, key = { it.id }) { playlist ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.confirm()
                            onPlaylistSelected(playlist)
                            scope.hideSheet(sheetState, onDismiss)
                        },
                    leadingContent = { Artwork(url = playlist.thumbnailUrl, modifier = Modifier.size(ArtworkSize.Row)) },
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.common_track_count, playlist.trackCount, playlist.trackCount))
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreatePlaylist(name)
                scope.hideSheet(sheetState, onDismiss)
            },
        )
    }
}

/** Dialogue de saisie du nom d'une nouvelle playlist. */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialName: String = "",
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val haptics = LocalAppHaptics.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_action_new_playlist)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_playlist_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.confirm()
                    onConfirm(name)
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}
