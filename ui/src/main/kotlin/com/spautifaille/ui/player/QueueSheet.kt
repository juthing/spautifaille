package com.spautifaille.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.QueueItem
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.NowPlayingIndicator
import com.spautifaille.ui.components.hideSheet
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val QueueHorizontalPadding = 16.dp

/**
 * File de lecture : le titre en cours est mis en avant dans une carte, puis « À suivre » (réordonnable par
 * poignée, avec un cran haptique à chaque rang franchi, et retrait par balayage). Toucher une ligne y saute.
 * Pendant un glissement on modifie une copie locale ; le déplacement n'est envoyé au lecteur qu'au relâchement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerState,
    actions: PlayerActions,
    onDismiss: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val current = state.queue.getOrNull(state.currentIndex)
    val offset = state.currentIndex + 1
    var upcoming by remember(state.queue, state.currentIndex) {
        mutableStateOf(state.queue.drop(offset.coerceAtLeast(0)))
    }
    // (uid, index d'origine dans `upcoming`) du glissement en cours.
    var dragOrigin by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val currentQueue by rememberUpdatedState(state.queue)
    val currentActions by rememberUpdatedState(actions)

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = upcoming.indexOfFirst { it.uid == from.key }
        val toIndex = upcoming.indexOfFirst { it.uid == to.key }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        if (dragOrigin == null) dragOrigin = (from.key as String) to fromIndex
        upcoming = upcoming.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        haptics.tick()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxHeight(0.85f)) {
            QueueHeader(
                canClear = state.queue.isNotEmpty(),
                onClear = {
                    haptics.confirm()
                    actions.onClearQueue()
                    scope.hideSheet(sheetState, onDismiss)
                },
            )
            if (state.shuffleEnabled) {
                // La liste montre l'ordre de la file, pas l'ordre réel de lecture.
                Row(
                    modifier = Modifier.padding(horizontal = QueueHorizontalPadding + 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.queue_shuffle_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = QueueHorizontalPadding),
            ) {
                if (current != null) {
                    item(key = "header-now") { SectionLabel(stringResource(R.string.queue_now_playing)) }
                    item(key = "now") { NowPlayingCard(item = current, isPlaying = state.isPlaying) }
                }
                item(key = "header-next") {
                    SectionLabel(
                        text = stringResource(R.string.queue_up_next),
                        count = if (upcoming.isEmpty()) null else pluralStringResource(
                            R.plurals.queue_up_next_count,
                            upcoming.size,
                            upcoming.size,
                        ),
                    )
                }
                if (upcoming.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.queue_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                }
                items(upcoming, key = { it.uid }) { item ->
                    ReorderableItem(reorderState, key = item.uid) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "dragElevation")
                        UpcomingRow(
                            item = item,
                            elevation = elevation,
                            onRemove = {
                                val index = currentQueue.indexOfFirst { it.uid == item.uid }
                                if (index >= 0) currentActions.onRemoveQueueItem(index)
                                upcoming = upcoming.filterNot { it.uid == item.uid }
                            },
                            onClick = {
                                val index = upcoming.indexOfFirst { it.uid == item.uid }
                                if (index >= 0) {
                                    haptics.click()
                                    actions.onSkipToQueueItem(offset + index)
                                }
                            },
                            dragHandle = {
                                Icon(
                                    imageVector = Icons.Filled.DragHandle,
                                    contentDescription = stringResource(R.string.queue_reorder),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .draggableHandle(
                                            onDragStarted = { haptics.longPress() },
                                            onDragStopped = {
                                                val origin = dragOrigin
                                                dragOrigin = null
                                                if (origin != null) {
                                                    val finalIndex = upcoming.indexOfFirst { it.uid == origin.first }
                                                    if (finalIndex >= 0 && finalIndex != origin.second) {
                                                        currentActions.onMoveQueueItem(offset + origin.second, offset + finalIndex)
                                                    }
                                                }
                                            },
                                        )
                                        .padding(12.dp),
                                )
                            },
                        )
                    }
                }
                item(key = "bottom-space") { Box(Modifier.navigationBarsPadding().size(16.dp)) }
            }
        }
    }
}

@Composable
private fun QueueHeader(canClear: Boolean, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = QueueHorizontalPadding + 8.dp, end = QueueHorizontalPadding - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.queue_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        TextButton(enabled = canClear, onClick = onClear) {
            Icon(Icons.Filled.ClearAll, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.queue_clear_short), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, count: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (count != null) {
            Text(
                text = count,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Carte du titre en cours : teinte d'accent, pochette plus grande et indicateur de lecture. */
@Composable
private fun NowPlayingCard(item: QueueItem, isPlaying: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                url = item.track.thumbnailUrl,
                modifier = Modifier.size(64.dp),
                shape = MaterialTheme.shapes.medium,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = item.track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NowPlayingIndicator(
                isAnimating = isPlaying,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun UpcomingRow(
    item: QueueItem,
    elevation: Dp,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val onRemoveState by rememberUpdatedState(onRemove)
    val shape = MaterialTheme.shapes.medium
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            haptics.confirm()
            onRemoveState()
        }
    }
    // Cran haptique quand le geste franchit (ou quitte) le seuil de suppression.
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) haptics.gestureThreshold()
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier
            .padding(vertical = 3.dp)
            .clip(shape),
        backgroundContent = {
            val swiping = dismissState.dismissDirection != SwipeToDismissBoxValue.Settled
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (swiping) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                if (swiping) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.queue_remove),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        Surface(
            onClick = onClick,
            shape = shape,
            shadowElevation = elevation,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Artwork(url = item.track.thumbnailUrl, modifier = Modifier.size(48.dp))
                },
                headlineContent = {
                    Text(
                        item.track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                supportingContent = { Text(item.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = dragHandle,
            )
        }
    }
}
