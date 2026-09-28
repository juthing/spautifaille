package com.spautifaille.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.ui.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val FabClearance = 96.dp
private val WideLayoutThreshold = 600.dp

/** Centre un contenu de liste et limite sa largeur sur grands écrans. */
@Composable
private fun CenteredContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = LibraryContentMaxWidth).fillMaxWidth().fillMaxSize()) { content() }
    }
}

// region Playlists

@Composable
internal fun LibraryPlaylistsTab(
    playlists: List<Playlist>,
    onOpenPlaylist: (Long) -> Unit,
    onPlay: (id: Long, shuffle: Boolean) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= WideLayoutThreshold) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = FabClearance),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist.id) },
                        onPlay = { shuffle -> onPlay(playlist.id, shuffle) },
                        onRename = { onRename(playlist) },
                        onDelete = { onDelete(playlist) },
                    )
                }
            }
        } else {
            CenteredContent {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            onClick = { onOpenPlaylist(playlist.id) },
                            onPlay = { shuffle -> onPlay(playlist.id, shuffle) },
                            onRename = { onRename(playlist) },
                            onDelete = { onDelete(playlist) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistArtwork(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    if (playlist.isSystem) {
        LibraryLikedArtwork(modifier, shape)
    } else {
        LibraryArtwork(playlist.thumbnailUrl, modifier, shape)
    }
}

/** Menu d'actions d'une playlist (bouton ⋮ ; ouvert aussi par appui long via [expanded]). */
@Composable
private fun PlaylistMenu(
    playlist: Playlist,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.lib_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            val playable = playlist.trackCount > 0
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_play)) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                enabled = playable,
                onClick = {
                    onExpandedChange(false)
                    onPlay(false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_shuffle)) },
                leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null) },
                enabled = playable,
                onClick = {
                    onExpandedChange(false)
                    onPlay(true)
                },
            )
            if (!playlist.isSystem) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lib_rename)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        onExpandedChange(false)
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lib_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        onExpandedChange(false)
                        onDelete()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(trackCountText(playlist.trackCount)) },
        leadingContent = { PlaylistArtwork(playlist, Modifier.size(56.dp)) },
        trailingContent = {
            PlaylistMenu(playlist, menuOpen, { menuOpen = it }, onPlay, onRename, onDelete)
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(8.dp),
    ) {
        PlaylistArtwork(playlist, Modifier.fillMaxWidth().aspectRatio(1f), shape = MaterialTheme.shapes.medium)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    trackCountText(playlist.trackCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PlaylistMenu(playlist, menuOpen, { menuOpen = it }, onPlay, onRename, onDelete)
        }
    }
}

// endregion

// region Téléchargés

@Composable
internal fun LibraryDownloadsTab(
    downloads: List<Download>,
    storage: StorageUsage?,
    onManage: () -> Unit,
    onPlay: (startIndex: Int, shuffle: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredContent(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (storage != null) {
                item(key = "storage") {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Storage, contentDescription = null) },
                        headlineContent = {
                            Text(stringResource(R.string.lib_downloads_storage_used, storageText(storage.downloadsBytes)))
                        },
                        supportingContent = { Text(trackCountText(storage.downloadCount)) },
                        trailingContent = {
                            FilledTonalButton(onClick = onManage) { Text(stringResource(R.string.lib_manage)) }
                        },
                    )
                }
            }
            if (downloads.isEmpty()) {
                item(key = "empty") {
                    LibraryEmptyState(
                        icon = Icons.Filled.DownloadDone,
                        title = stringResource(R.string.lib_downloads_empty_title),
                        body = stringResource(R.string.lib_downloads_empty_body),
                    )
                }
            } else {
                item(key = "actions") {
                    PlayShuffleRow(
                        onPlay = { onPlay(0, false) },
                        onShuffle = { onPlay(0, true) },
                    )
                }
                items(downloads.size, key = { downloads[it].track.id }) { index ->
                    val track = downloads[index].track
                    LibraryTrackRow(track, onClick = { onPlay(index, false) })
                }
            }
        }
    }
}

@Composable
internal fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
            Text(stringResource(R.string.lib_play), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, Modifier.size(18.dp))
            Text(stringResource(R.string.lib_shuffle), Modifier.padding(start = 8.dp))
        }
    }
}

// endregion

// region Historique

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryHistoryTab(
    history: List<HistoryEntry>,
    onPlay: (index: Int) -> Unit,
    onRemove: (id: Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    val items = remember(history, today, zone) { groupHistoryByDay(history, today, zone) }
    CenteredContent(modifier) {
        if (history.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.History,
                title = stringResource(R.string.lib_history_empty_title),
                body = stringResource(R.string.lib_history_empty_body),
            )
            return@CenteredContent
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "clear") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.lib_clear_history), Modifier.padding(start = 8.dp))
                    }
                }
            }
            items.forEach { item ->
                when (item) {
                    is HistoryListItem.Header -> stickyHeader(key = "header-${item.date}") {
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                            Text(
                                text = historyDayLabel(item),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    is HistoryListItem.Entry -> item(key = "entry-${item.entry.id}") {
                        LibraryTrackRow(
                            track = item.entry.track,
                            onClick = { onPlay(item.index) },
                            trailing = {
                                IconButton(onClick = { onRemove(item.entry.id) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.lib_history_remove),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun historyDayLabel(header: HistoryListItem.Header): String = when (val day = header.day) {
    HistoryDay.Today -> stringResource(R.string.lib_today)
    HistoryDay.Yesterday -> stringResource(R.string.lib_yesterday)
    is HistoryDay.On -> {
        val locale = Locale.getDefault()
        val pattern = if (day.date.year == LocalDate.now().year) "EEEE d MMMM" else "EEEE d MMMM yyyy"
        DateTimeFormatter.ofPattern(pattern, locale).format(day.date)
            .replaceFirstChar { it.titlecase(locale) }
    }
}

// endregion

// region Artistes

@Composable
internal fun LibraryArtistsTab(
    artists: List<Artist>,
    onOpenArtist: (String) -> Unit,
    onUnsubscribe: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredContent(modifier) {
        if (artists.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.lib_artists_empty_title),
                body = stringResource(R.string.lib_artists_empty_body),
            )
            return@CenteredContent
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(artists, key = { it.url }) { artist ->
                ListItem(
                    headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = artist.subscriberCount?.let { count ->
                        { Text(stringResource(R.string.lib_subscribers, formatCompactCount(count))) }
                    },
                    leadingContent = {
                        LibraryArtwork(
                            url = artist.avatarUrl,
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            fallbackIcon = Icons.Filled.Person,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onUnsubscribe(artist.url) }) {
                            Icon(
                                Icons.Filled.PersonRemove,
                                contentDescription = stringResource(R.string.lib_unsubscribe_from, artist.name),
                            )
                        }
                    },
                    modifier = Modifier.clickable { onOpenArtist(artist.url) },
                )
            }
        }
    }
}

// endregion
