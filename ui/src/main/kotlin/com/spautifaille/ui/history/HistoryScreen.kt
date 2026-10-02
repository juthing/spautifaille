package com.spautifaille.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.components.TrackListPlaceholder
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Actions de l'écran Historique. */
@Immutable
data class HistoryActions(
    val onPlayFrom: (index: Int) -> Unit = {},
    val onRemove: (entryId: Long) -> Unit = {},
    val onClear: () -> Unit = {},
)

@Composable
fun HistoryScreenRoot(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenArtist: ((artistUrl: String) -> Unit)? = null,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        HistoryActions(onPlayFrom = viewModel::playFrom, onRemove = viewModel::remove, onClear = viewModel::clear)
    }
    HistoryScreen(
        state = state,
        actions = actions,
        onBack = onBack,
        modifier = modifier,
        onOpenArtist = onOpenArtist,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    actions: HistoryActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenArtist: ((artistUrl: String) -> Unit)? = null,
) {
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }, enabled = state.entries.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.history_clear))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                state.isLoading -> TrackListPlaceholder(Modifier.widthIn(max = ContentMaxWidth))
                state.entries.isEmpty() -> EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    message = stringResource(R.string.history_empty_message),
                    icon = Icons.Filled.History,
                )
                else -> HistoryList(
                    state = state,
                    actions = actions,
                    onMoreClick = { actionsTrack = it },
                )
            }
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            onDismiss = { actionsTrack = null },
            onGoToArtist = onOpenArtist,
        )
    }
    if (showClearDialog) {
        val haptics = LocalAppHaptics.current
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.history_clear)) },
            text = { Text(stringResource(R.string.history_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    actions.onClear()
                    showClearDialog = false
                }) { Text(stringResource(R.string.history_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

@Composable
private fun HistoryList(
    state: HistoryUiState,
    actions: HistoryActions,
    onMoreClick: (Track) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxHeight(),
        contentPadding = PaddingValues(bottom = ListBottomPadding),
    ) {
        state.items.forEach { item ->
            when (item) {
                is HistoryListItem.Header -> stickyHeader(key = "header-${item.date}") {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                        Text(
                            text = historyDayLabel(item),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s)
                                .semantics { heading() },
                        )
                    }
                }
                is HistoryListItem.Entry -> item(key = "entry-${item.entry.id}") {
                    HistoryRow(
                        entry = item.entry,
                        isCurrent = item.entry.track.id == state.currentTrackId,
                        isPlaying = state.isPlaying,
                        isAvailable = state.isAvailable(item.entry.track.id),
                        onClick = { actions.onPlayFrom(item.index) },
                        onMoreClick = { onMoreClick(item.entry.track) },
                        onRemove = { actions.onRemove(item.entry.id) },
                    )
                }
            }
        }
    }
}

/** Ligne d'historique : appui = lecture, appui long = actions, glisser vers la gauche = retirer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isAvailable: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val currentOnRemove by rememberUpdatedState(onRemove)
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState) {
        // Cran dès que le geste franchit son seuil (et non à chaque pixel)…
        launch {
            snapshotFlow { dismissState.targetValue }.collect { target ->
                if (target == SwipeToDismissBoxValue.EndToStart) haptics.gestureThreshold()
            }
        }
        // … puis retrait quand la ligne a fini de glisser hors de l'écran.
        snapshotFlow { dismissState.currentValue }.first { it == SwipeToDismissBoxValue.EndToStart }
        haptics.confirm()
        currentOnRemove()
    }
    val removeLabel = stringResource(R.string.history_remove)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(removeLabel) {
                onRemove()
                true
            })
        },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ScreenHorizontalPadding),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            TrackListItem(
                track = entry.track,
                onClick = onClick,
                onMoreClick = onMoreClick,
                isCurrent = isCurrent,
                isPlaying = isPlaying,
                isAvailable = isAvailable,
                modifier = Modifier.padding(horizontal = Spacing.s),
            )
        }
    }
}

@Composable
private fun historyDayLabel(header: HistoryListItem.Header): String = when (val day = header.day) {
    HistoryDay.Today -> stringResource(R.string.history_today)
    HistoryDay.Yesterday -> stringResource(R.string.history_yesterday)
    is HistoryDay.On -> {
        val locale = LocalConfiguration.current.locales[0]
        val pattern = if (day.date.year == LocalDate.now().year) "EEEE d MMMM" else "EEEE d MMMM yyyy"
        DateTimeFormatter.ofPattern(pattern, locale).format(day.date)
            .replaceFirstChar { it.titlecase(locale) }
    }
}

private fun previewState(): HistoryUiState {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val entries = List(8) { index ->
        HistoryEntry(
            id = index.toLong(),
            track = Track(id = "id$index", title = "Titre écouté $index", artist = "Artiste $index", durationMs = 200_000L),
            playedAt = now - index * 7_200_000L,
        )
    }
    return HistoryUiState(
        isLoading = false,
        entries = entries,
        items = groupHistoryByDay(entries, today, zone),
        currentTrackId = "id1",
        isPlaying = true,
    )
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    SpautifailleTheme(dynamicColor = false) {
        HistoryScreen(state = previewState(), actions = HistoryActions(), onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        HistoryScreen(state = HistoryUiState(isLoading = false), actions = HistoryActions(), onBack = {})
    }
}
