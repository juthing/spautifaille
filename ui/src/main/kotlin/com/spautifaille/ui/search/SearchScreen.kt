package com.spautifaille.ui.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.components.TrackListPlaceholder
import com.spautifaille.ui.components.formatCompactCount
import com.spautifaille.ui.theme.SpautifailleTheme

private const val LOAD_MORE_THRESHOLD = 5
private val SearchBarSpace = 72.dp

@Composable
fun SearchScreenRoot(
    onOpenPlaylist: (url: String) -> Unit,
    onOpenArtist: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::onSearch,
        onFilterSelected = viewModel::onFilterSelected,
        onTrackClick = viewModel::onTrackClick,
        onLoadMore = viewModel::onLoadMore,
        onRetry = viewModel::onRetry,
        onClearRecent = viewModel::onClearRecent,
        onRemoveRecent = viewModel::onRemoveRecent,
        onOpenPlaylist = onOpenPlaylist,
        onOpenArtist = onOpenArtist,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFilterSelected: (SearchFilter) -> Unit,
    onTrackClick: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onClearRecent: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveRecent: (String) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    val horizontalPadding by animateDpAsState(if (expanded) 0.dp else 16.dp, label = "searchBarPadding")
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset + SearchBarSpace)
                .semantics { traversalIndex = 1f },
        ) {
            FilterRow(selected = state.filter, onSelected = onFilterSelected)
            ResultsContent(
                state = state,
                onTrackClick = onTrackClick,
                onTrackMore = { actionsTrack = it },
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                onOpenPlaylist = onOpenPlaylist,
                onOpenArtist = onOpenArtist,
            )
        }

        SearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .semantics { traversalIndex = 0f },
            inputField = {
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    onSearch = {
                        onSearch(it)
                        expanded = false
                    },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = {
                        if (expanded) {
                            IconButton(onClick = { expanded = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                            }
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        }
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_action_clear))
                            }
                        }
                    },
                )
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            SuggestionsContent(
                query = state.query,
                suggestions = state.suggestions,
                recents = state.recentQueries,
                onPick = {
                    onSearch(it)
                    expanded = false
                },
                onFill = onQueryChange,
                onClearRecent = onClearRecent,
                onRemoveRecent = onRemoveRecent,
            )
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            onDismiss = { actionsTrack = null },
            onGoToArtist = onOpenArtist,
        )
    }
}

@Composable
private fun FilterRow(selected: SearchFilter, onSelected: (SearchFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SearchFilters, key = { it.name }) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelected(filter) },
                label = { Text(stringResource(filter.labelRes())) },
            )
        }
    }
}

private fun SearchFilter.labelRes(): Int = when (this) {
    SearchFilter.SONGS -> R.string.search_filter_songs
    SearchFilter.VIDEOS -> R.string.search_filter_videos
    SearchFilter.ALBUMS -> R.string.search_filter_albums
    SearchFilter.PLAYLISTS -> R.string.search_filter_playlists
    SearchFilter.ARTISTS -> R.string.search_filter_artists
}

@Composable
private fun ResultsContent(
    state: SearchUiState,
    onTrackClick: (Track) -> Unit,
    onTrackMore: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    when {
        state.submittedQuery == null -> EmptyState(
            title = stringResource(R.string.search_idle_title),
            message = stringResource(R.string.search_idle_message),
            icon = Icons.Filled.Search,
        )
        state.isLoading -> TrackListPlaceholder()
        state.error != null -> ErrorState(message = state.error.toMessage(), onRetry = onRetry)
        state.results.isEmpty() -> EmptyState(
            title = stringResource(R.string.search_no_results_title),
            message = stringResource(R.string.search_no_results_message, state.submittedQuery),
            icon = Icons.Filled.Search,
        )
        else -> ResultsList(
            state = state,
            onTrackClick = onTrackClick,
            onTrackMore = onTrackMore,
            onLoadMore = onLoadMore,
            onRetry = onRetry,
            onOpenPlaylist = onOpenPlaylist,
            onOpenArtist = onOpenArtist,
        )
    }
}

@Composable
private fun ResultsList(
    state: SearchUiState,
    onTrackClick: (Track) -> Unit,
    onTrackMore: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(state.submittedQuery, state.filter) { listState.scrollToItem(0) }
    LaunchedEffect(nearEnd, state.results.size, state.hasMore) {
        if (nearEnd && state.hasMore && !state.isLoadingMore && state.loadMoreError == null) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        items(state.results, key = { resultKey(it) }, contentType = { it::class }) { result ->
            when (result) {
                is SearchResult.TrackResult -> TrackListItem(
                    track = result.track,
                    isCurrent = result.track.id == state.nowPlayingId,
                    onClick = { onTrackClick(result.track) },
                    onMoreClick = { onTrackMore(result.track) },
                )
                is SearchResult.PlaylistResult -> PlaylistResultItem(result.playlist) { onOpenPlaylist(result.playlist.url) }
                is SearchResult.ArtistResult -> ArtistResultItem(result.artist) { onOpenArtist(result.artist.url) }
            }
        }
        if (state.isLoadingMore || state.loadMoreError != null) {
            item(key = "footer") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loadMoreError != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(state.loadMoreError.toMessage()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_action_retry)) }
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

private fun resultKey(result: SearchResult): String = when (result) {
    is SearchResult.TrackResult -> "t:" + result.track.id
    is SearchResult.PlaylistResult -> "p:" + result.playlist.url
    is SearchResult.ArtistResult -> "a:" + result.artist.url
}

@Composable
private fun PlaylistResultItem(playlist: RemotePlaylist, onClick: () -> Unit) {
    val count = playlist.trackCount?.toInt()?.let { pluralStringResource(R.plurals.common_track_count, it, it) }
    val subtitle = listOfNotNull(playlist.uploader, count).joinToString(" · ")
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Artwork(url = playlist.thumbnailUrl, modifier = Modifier.size(52.dp)) },
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
private fun ArtistResultItem(artist: Artist, onClick: () -> Unit) {
    val subscribers = artist.subscriberCount?.let { stringResource(R.string.common_artist_subscribers, formatCompactCount(it)) }
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Artwork(
                url = artist.avatarUrl,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                placeholderIcon = Icons.Filled.Person,
            )
        },
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subscribers?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
private fun SuggestionsContent(
    query: String,
    suggestions: List<String>,
    recents: List<String>,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
    onClearRecent: () -> Unit,
    onRemoveRecent: (String) -> Unit,
) {
    val showRecents = query.isBlank()
    val entries = if (showRecents) recents else suggestions
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (showRecents && recents.isNotEmpty()) {
            item(key = "recents-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.search_recent_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearRecent) { Text(stringResource(R.string.search_clear_history)) }
                }
            }
        }
        items(entries, key = { (if (showRecents) "r:" else "s:") + it }) { entry ->
            ListItem(
                modifier = Modifier.clickable { onPick(entry) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(if (showRecents) Icons.Filled.History else Icons.Filled.Search, contentDescription = null)
                },
                headlineContent = { Text(entry, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onFill(entry) }) {
                            Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = stringResource(R.string.search_use_suggestion))
                        }
                        if (showRecents) {
                            IconButton(onClick = { onRemoveRecent(entry) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_remove_recent))
                            }
                        }
                    }
                },
            )
        }
    }
}

private val PreviewTracks = List(4) { i ->
    SearchResult.TrackResult(Track(id = "id$i", title = "Titre numéro $i", artist = "Artiste", durationMs = 200_000L + i * 1000))
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenResultsPreview() {
    SpautifailleTheme(dynamicColor = false) {
        SearchScreen(
            state = SearchUiState(query = "titre", submittedQuery = "titre", results = PreviewTracks, nowPlayingId = "id1"),
            onQueryChange = {}, onSearch = {}, onFilterSelected = {}, onTrackClick = {}, onLoadMore = {},
            onRetry = {}, onClearRecent = {}, onOpenPlaylist = {}, onOpenArtist = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenErrorPreview() {
    SpautifailleTheme(dynamicColor = false) {
        SearchScreen(
            state = SearchUiState(query = "titre", submittedQuery = "titre", error = AppError.Network),
            onQueryChange = {}, onSearch = {}, onFilterSelected = {}, onTrackClick = {}, onLoadMore = {},
            onRetry = {}, onClearRecent = {}, onOpenPlaylist = {}, onOpenArtist = {},
        )
    }
}
