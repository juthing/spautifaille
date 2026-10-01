package com.spautifaille.ui.downloads

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.IconTone
import com.spautifaille.ui.components.LoadingState
import com.spautifaille.ui.components.SectionHeader
import com.spautifaille.ui.components.ToneIconCircle
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.theme.SpautifailleTheme

@Immutable
data class DownloadsActions(
    val onBack: () -> Unit = {},
    val onPlay: (index: Int) -> Unit = {},
    val onCancel: (trackId: String) -> Unit = {},
    val onRetry: (trackId: String) -> Unit = {},
    val onRetryAll: () -> Unit = {},
    val onDelete: (trackId: String) -> Unit = {},
    val onDeleteAll: () -> Unit = {},
    val onTrackMore: (Track) -> Unit = {},
    val onAllowMobileData: () -> Unit = {},
)

/** Écran de gestion des téléchargements (à brancher sur `DownloadsRoute`). */
@Composable
fun DownloadsScreenRoot(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    val actions = remember(viewModel, onBack) {
        DownloadsActions(
            onBack = onBack,
            onPlay = viewModel::play,
            onCancel = viewModel::cancel,
            onRetry = viewModel::retry,
            onRetryAll = viewModel::retryAllFailed,
            onDelete = viewModel::delete,
            onDeleteAll = viewModel::deleteAll,
            onTrackMore = { actionsTrack = it },
            onAllowMobileData = viewModel::allowMobileData,
        )
    }
    DownloadsScreen(state = state, actions = actions, modifier = modifier)
    actionsTrack?.let { track ->
        TrackActionsSheet(track = track, onDismiss = { actionsTrack = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
) {
    var showDeleteAll by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalAppHaptics.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dl_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                    }
                },
                actions = {
                    OverflowMenu(
                        deleteAllEnabled = state.active.isNotEmpty() || state.completed.isNotEmpty(),
                        onDeleteAll = { showDeleteAll = true },
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                state.isLoading -> LoadingState()
                state.isEmpty -> EmptyState(
                    title = stringResource(R.string.dl_empty_title),
                    message = stringResource(R.string.dl_empty_message),
                    icon = Icons.Filled.DownloadDone,
                )
                else -> DownloadsList(state = state, actions = actions)
            }
        }
    }

    if (showDeleteAll) {
        val count = state.active.size + state.completed.size
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text(stringResource(R.string.dl_delete_all_title)) },
            text = { Text(pluralStringResource(R.plurals.dl_delete_all_message, count, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        showDeleteAll = false
                        actions.onDeleteAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.common_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAll = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
private fun OverflowMenu(deleteAllEnabled: Boolean, onDeleteAll: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.dl_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dl_delete_all)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                enabled = deleteAllEnabled,
                onClick = {
                    expanded = false
                    onDeleteAll()
                },
            )
        }
    }
}

@Composable
private fun DownloadsList(state: DownloadsUiState, actions: DownloadsActions) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ListBottomPadding),
    ) {
        if (state.waitingForWifi) {
            item(key = "wifi-waiting") {
                WifiWaitingBanner(
                    onAllowMobileData = actions.onAllowMobileData,
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
                )
            }
        }
        state.storage?.let { storage ->
            item(key = "storage") { StorageCard(storage, Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s)) }
        }
        if (state.active.isNotEmpty()) {
            item(key = "header-active") {
                SectionHeader(
                    title = stringResource(R.string.dl_section_active_count, state.active.size),
                    actionLabel = if (state.failedCount > 0) stringResource(R.string.dl_retry_all) else null,
                    onAction = if (state.failedCount > 0) actions.onRetryAll else null,
                )
            }
            items(state.active, key = { "active-${it.track.id}" }) { download ->
                ActiveDownloadRow(download = download, actions = actions)
            }
        }
        if (state.completed.isNotEmpty()) {
            item(key = "header-done") {
                SectionHeader(title = stringResource(R.string.dl_section_done_count, state.completed.size))
            }
            itemsIndexed(state.completed, key = { _, it -> "done-${it.track.id}" }) { index, download ->
                CompletedDownloadRow(
                    download = download,
                    onPlay = { actions.onPlay(index) },
                    onMore = { actions.onTrackMore(download.track) },
                    onDelete = { actions.onDelete(download.track.id) },
                )
            }
        }
    }
}

/** Explique pourquoi la file n'avance pas (réglage « Wi-Fi uniquement » sans Wi-Fi) et permet de lever la restriction. */
@Composable
private fun WifiWaitingBanner(onAllowMobileData: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WifiOff, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.dl_wifi_waiting_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = stringResource(R.string.dl_wifi_waiting_message),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onAllowMobileData, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.dl_wifi_allow_mobile))
            }
        }
    }
}

// region Stockage

@Composable
private fun StorageCard(storage: StorageUsage, modifier: Modifier = Modifier) {
    val total = storage.downloadsBytes + storage.cacheBytes
    val downloadsShare = if (total > 0) storage.downloadsBytes.toFloat() / total else 0f
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToneIconCircle(icon = Icons.Filled.Storage, tone = IconTone.Tertiary)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dl_storage_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = fileSize(total), style = MaterialTheme.typography.headlineSmall)
                }
            }
            // Part des téléchargements (primaire) face au cache de lecture (tertiaire) dans le stockage utilisé.
            LinearProgressIndicator(
                progress = { downloadsShare },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = if (total > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                LegendRow(
                    color = MaterialTheme.colorScheme.primary,
                    label = stringResource(R.string.dl_storage_downloads),
                    detail = pluralStringResource(
                        R.plurals.dl_storage_downloads_detail,
                        storage.downloadCount,
                        fileSize(storage.downloadsBytes),
                        storage.downloadCount,
                    ),
                )
                LegendRow(
                    color = MaterialTheme.colorScheme.tertiary,
                    label = stringResource(R.string.dl_storage_cache),
                    detail = fileSize(storage.cacheBytes),
                )
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(Spacing.s))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

// endregion

// region Lignes

@Composable
private fun ActiveDownloadRow(download: Download, actions: DownloadsActions, modifier: Modifier = Modifier) {
    val failed = download.state == DownloadState.FAILED
    val haptics = LocalAppHaptics.current
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Artwork(url = download.track.thumbnailUrl, modifier = Modifier.size(ArtworkSize.Row)) },
        headlineContent = {
            Text(text = download.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = statusText(download),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (download.state == DownloadState.RUNNING) ProgressBar(download.progress)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (failed) {
                    IconButton(onClick = {
                        haptics.click()
                        actions.onRetry(download.track.id)
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_action_retry))
                    }
                }
                IconButton(onClick = {
                    haptics.click()
                    actions.onCancel(download.track.id)
                }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(if (failed) R.string.dl_remove_failed else R.string.common_action_cancel_download),
                    )
                }
            }
        },
    )
}

@Composable
private fun ProgressBar(progress: Float?) {
    if (progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        val animated by animateFloatAsState(targetValue = progress, label = "downloadProgress")
        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun statusText(download: Download): String = when (download.state) {
    DownloadState.QUEUED -> stringResource(R.string.dl_status_queued)
    DownloadState.PAUSED -> stringResource(R.string.dl_status_paused)
    DownloadState.FAILED ->
        download.error?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.dl_status_failed_reason, it) }
            ?: stringResource(R.string.dl_status_failed)
    DownloadState.COMPLETED -> ""
    DownloadState.RUNNING -> {
        val percent = download.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        val total = download.totalBytes
        when {
            percent == null -> stringResource(R.string.dl_status_starting)
            total != null && total > 0 -> stringResource(
                R.string.dl_status_running_size,
                percent,
                fileSize(download.downloadedBytes),
                fileSize(total),
            )
            else -> stringResource(R.string.dl_status_running, percent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompletedDownloadRow(
    download: Download,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val dismissState = rememberSwipeToDismissBoxState()
    // Cran quand le balayage franchit le seuil de suppression, confirmation quand le titre est supprimé.
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) haptics.gestureThreshold()
    }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            haptics.confirm()
            onDelete()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .padding(horizontal = Spacing.s)
            .clip(MaterialTheme.shapes.large),
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
                    contentDescription = stringResource(R.string.common_action_delete_download),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
            TrackListItem(track = download.track, onClick = onPlay, onMoreClick = onMore)
        }
    }
}

// endregion

// region Aperçus

private fun previewTrack(i: Int) = Track(
    id = "id$i",
    title = "Titre numéro $i avec un nom assez long pour être tronqué",
    artist = "Artiste $i",
    durationMs = 200_000,
)

private fun previewDownload(i: Int, state: DownloadState, downloaded: Long = 0, total: Long? = null, error: String? = null) =
    Download(
        track = previewTrack(i),
        state = state,
        progress = total?.takeIf { it > 0 }?.let { downloaded.toFloat() / it },
        downloadedBytes = downloaded,
        totalBytes = total,
        filePath = null,
        error = error,
        createdAt = i.toLong(),
    )

private val previewState = DownloadsUiState(
    isLoading = false,
    active = listOf(
        previewDownload(1, DownloadState.RUNNING, 3_100_000, 7_400_000),
        previewDownload(2, DownloadState.QUEUED),
        previewDownload(3, DownloadState.FAILED, error = "Connexion impossible"),
    ),
    completed = (4..7).map { previewDownload(it, DownloadState.COMPLETED, 5_000_000, 5_000_000) },
    storage = StorageUsage(downloadsBytes = 20_000_000, cacheBytes = 45_000_000, downloadCount = 4),
    waitingForWifi = true,
)

@Preview(showBackground = true)
@Composable
private fun DownloadsScreenPreview() {
    SpautifailleTheme(dynamicColor = false) { DownloadsScreen(previewState, DownloadsActions()) }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DownloadsScreenDarkPreview() {
    SpautifailleTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { DownloadsScreen(previewState, DownloadsActions()) }
}

@Preview(showBackground = true)
@Composable
private fun DownloadsScreenEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        DownloadsScreen(DownloadsUiState(isLoading = false), DownloadsActions())
    }
}
// endregion
