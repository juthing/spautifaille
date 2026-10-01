package com.spautifaille.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Feuille d'actions d'un titre : lire ensuite, ajouter à la file, à une playlist, J'aime, télécharger,
 * aller à l'artiste. [onGoToArtist] est nul quand la navigation vers l'artiste n'a pas de sens (déjà sur sa page).
 * [onRemove] (facultatif, libellé [removeLabel]) ajoute une action contextuelle en bas de feuille, par exemple
 * « Retirer de la playlist ».
 */
@Composable
fun TrackActionsSheet(
    track: Track,
    onDismiss: () -> Unit,
    onGoToArtist: ((artistUrl: String) -> Unit)? = null,
    @StringRes removeLabel: Int = R.string.lib_remove_from_playlist,
    onRemove: (() -> Unit)? = null,
    viewModel: TrackActionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(track.id) { viewModel.select(track.id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPlaylistPicker by remember(track.id) { mutableStateOf(false) }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(tracks = listOf(track), onDismiss = onDismiss)
        return
    }

    TrackActionsSheetContent(
        track = track,
        state = state,
        onDismiss = onDismiss,
        onPlayNext = { viewModel.playNext(track) },
        onAddToQueue = { viewModel.addToQueue(track) },
        onAddToPlaylist = { showPlaylistPicker = true },
        onToggleLike = { viewModel.toggleLike(track) },
        onToggleDownload = { viewModel.toggleDownload(track) },
        onGoToArtist = track.artistUrl?.let { url -> onGoToArtist?.let { go -> { go(url) } } },
        removeLabel = removeLabel,
        onRemove = onRemove,
    )
}

/** Version sans état de [TrackActionsSheet] (prévisualisable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheetContent(
    track: Track,
    state: TrackActionsState,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDownload: () -> Unit,
    onGoToArtist: (() -> Unit)?,
    @StringRes removeLabel: Int = R.string.lib_remove_from_playlist,
    onRemove: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            ListItem(
                leadingContent = { Artwork(url = track.thumbnailUrl, modifier = Modifier.size(52.dp)) },
                headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            HorizontalDivider()
            ActionItem(Icons.AutoMirrored.Filled.PlaylistPlay, R.string.common_action_play_next) {
                onPlayNext(); scope.hideSheet(sheetState, onDismiss)
            }
            ActionItem(Icons.AutoMirrored.Filled.QueueMusic, R.string.common_action_add_to_queue) {
                onAddToQueue(); scope.hideSheet(sheetState, onDismiss)
            }
            ActionItem(Icons.AutoMirrored.Filled.PlaylistAdd, R.string.common_action_add_to_playlist) {
                onAddToPlaylist()
            }
            ActionItem(
                icon = if (state.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = if (state.isLiked) R.string.common_action_unlike else R.string.common_action_like,
            ) {
                onToggleLike(); scope.hideSheet(sheetState, onDismiss)
            }
            ActionItem(
                icon = when (state.downloadStatus) {
                    DownloadStatus.NONE -> Icons.Filled.Download
                    DownloadStatus.IN_PROGRESS -> Icons.Filled.Close
                    DownloadStatus.DONE -> Icons.Filled.Delete
                },
                label = when (state.downloadStatus) {
                    DownloadStatus.NONE -> R.string.common_action_download
                    DownloadStatus.IN_PROGRESS -> R.string.common_action_cancel_download
                    DownloadStatus.DONE -> R.string.common_action_delete_download
                },
            ) {
                onToggleDownload(); scope.hideSheet(sheetState, onDismiss)
            }
            if (onGoToArtist != null) {
                ActionItem(Icons.Filled.Person, R.string.common_action_go_to_artist) {
                    onGoToArtist(); scope.hideSheet(sheetState, onDismiss)
                }
            }
            if (onRemove != null) {
                ActionItem(Icons.Filled.Delete, removeLabel) {
                    onRemove(); scope.hideSheet(sheetState, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    val haptics = LocalAppHaptics.current
    ListItem(
        modifier = Modifier.clickable {
            haptics.click()
            onClick()
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(stringResource(label)) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

/** Masque la feuille avec son animation puis appelle [onDismiss]. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun CoroutineScope.hideSheet(sheetState: SheetState, onDismiss: () -> Unit) {
    launch { sheetState.hide() }.invokeOnCompletion {
        if (!sheetState.isVisible) onDismiss()
    }
}
