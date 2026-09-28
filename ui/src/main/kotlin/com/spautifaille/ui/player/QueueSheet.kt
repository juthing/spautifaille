package com.spautifaille.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.QueueItem
import com.spautifaille.ui.R
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.hideSheet
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * File de lecture : titre en cours puis « À suivre » réordonnable (poignée), avec balayage pour retirer
 * un élément et toucher pour y sauter. Pendant un glissement on modifie une copie locale ; le déplacement
 * n'est envoyé au lecteur qu'au relâchement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerState,
    actions: PlayerActions,
    onDismiss: () -> Unit,
) {
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
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxHeight(0.85f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge)
                TextButton(
                    enabled = state.queue.isNotEmpty(),
                    onClick = {
                        actions.onClearQueue()
                        scope.hideSheet(sheetState, onDismiss)
                    },
                ) {
                    Icon(Icons.Filled.ClearAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.queue_clear), modifier = Modifier.padding(start = 8.dp))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                if (current != null) {
                    item(key = "header-now") { SectionLabel(stringResource(R.string.queue_now_playing)) }
                    item(key = "now") {
                        QueueRow(item = current, highlighted = true, onClick = {}, trailing = {})
                    }
                }
                item(key = "header-next") { SectionLabel(stringResource(R.string.queue_up_next)) }
                if (upcoming.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.queue_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
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
                                if (index >= 0) actions.onSkipToQueueItem(offset + index)
                            },
                            dragHandle = {
                                Icon(
                                    imageVector = Icons.Filled.DragHandle,
                                    contentDescription = stringResource(R.string.queue_reorder),
                                    modifier = Modifier
                                        .draggableHandle(
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
                item(key = "bottom-space") { Box(Modifier.navigationBarsPadding().size(8.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun QueueRow(
    item: QueueItem,
    highlighted: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = containerColor),
        leadingContent = { Artwork(url = item.track.thumbnailUrl, modifier = Modifier.size(48.dp)) },
        headlineContent = {
            Text(
                item.track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Unspecified,
                fontWeight = if (highlighted) FontWeight.SemiBold else null,
            )
        },
        supportingContent = { Text(item.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = trailing,
    )
}

@Composable
private fun UpcomingRow(
    item: QueueItem,
    elevation: androidx.compose.ui.unit.Dp,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val onRemoveState by rememberUpdatedState(onRemove)
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) onRemoveState()
    }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.queue_remove),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Surface(shadowElevation = elevation, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            QueueRow(
                item = item,
                highlighted = false,
                onClick = onClick,
                trailing = dragHandle,
            )
        }
    }
}
