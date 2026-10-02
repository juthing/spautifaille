package com.spautifaille.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.spautifaille.ui.components.SectionHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
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
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.components.IconTone
import com.spautifaille.ui.components.ToneIconCircle
import com.spautifaille.ui.components.TrackListPlaceholder
import com.spautifaille.ui.components.formatCompactCount
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

private const val LOAD_MORE_THRESHOLD = 5
private val SearchBarSpace = 72.dp

@Composable
fun SearchScreenRoot(
    onBack: () -> Unit,
    onOpenPlaylist: (url: String) -> Unit,
    onOpenArtist: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
    recognitionViewModel: RecognitionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recognitionState by recognitionViewModel.uiState.collectAsStateWithLifecycle()
    val startRecognition = rememberRecognitionStarter(recognitionViewModel, recognitionState)
    // Un titre reconnu lance la recherche « titre artiste » dans cet écran.
    LaunchedEffect(recognitionViewModel) {
        recognitionViewModel.events.collect { event ->
            when (event) {
                is RecognitionEvent.SearchFor -> viewModel.onRecognizedQuery(event.query)
            }
        }
    }
    SearchScreen(
        state = state,
        onBack = onBack,
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
        onRecognizeClick = startRecognition,
        modifier = modifier,
    )
    if (recognitionState != RecognitionUiState.Idle) {
        RecognitionSheet(
            state = recognitionState,
            bestResult = state.results.filterIsInstance<SearchResult.TrackResult>().firstOrNull()?.track,
            isSearching = state.isLoading,
            onRetry = startRecognition,
            onDismiss = recognitionViewModel::dismiss,
            onPlay = viewModel::onTrackClick,
        )
    }
}

/**
 * Recherche, poussée depuis l'Accueil : le champ prend le focus à la première arrivée (clavier ouvert),
 * la flèche et le retour système ramènent à l'Accueil. Tant que le champ a le focus, les suggestions
 * (ou l'historique) remplacent les résultats ; valider la recherche referme le clavier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
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
    onRecognizeClick: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val haptics = LocalAppHaptics.current
    // Vrai tant que le champ a le focus (saisie en cours).
    var typing by remember { mutableStateOf(false) }
    // Focus automatique une seule fois : pas à chaque recomposition ni au retour depuis un résultat.
    var autoFocusPending by rememberSaveable { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(Unit) {
        if (autoFocusPending) {
            autoFocusPending = false
            if (state.submittedQuery == null) focusRequester.requestFocus()
        }
    }

    Box(modifier = modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset + SearchBarSpace)
                .semantics { traversalIndex = 1f },
        ) {
            if (typing) {
                SuggestionsContent(
                    query = state.query,
                    suggestions = state.suggestions,
                    recents = state.recentQueries,
                    onPick = {
                        onSearch(it)
                        typing = false
                    },
                    onFill = onQueryChange,
                    onClearRecent = onClearRecent,
                    onRemoveRecent = onRemoveRecent,
                )
            } else {
                FilterRow(selected = state.filter, onSelected = onFilterSelected)
                ResultsContent(
                    state = state,
                    onSearch = onSearch,
                    onClearRecent = onClearRecent,
                    onRemoveRecent = onRemoveRecent,
                    onTrackClick = onTrackClick,
                    onTrackMore = { actionsTrack = it },
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenArtist = onOpenArtist,
                )
            }
        }

        // Barre repliée : seul le champ est utilisé, les suggestions sont affichées par l'écran lui-même.
        SearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding)
                .semantics { traversalIndex = 0f },
            inputField = {
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    onSearch = {
                        onSearch(it)
                        typing = false
                    },
                    expanded = typing,
                    onExpandedChange = { typing = it },
                    modifier = Modifier.focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = {
                                    onQueryChange("")
                                    focusRequester.requestFocus()
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_action_clear))
                                }
                            }
                            // Reconnaissance musicale : on referme le clavier pour laisser place à la feuille d'écoute.
                            IconButton(onClick = {
                                haptics.click()
                                typing = false
                                focusManager.clearFocus()
                                onRecognizeClick()
                            }) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = stringResource(R.string.search_recognize_action))
                            }
                        }
                    },
                )
            },
            expanded = false,
            onExpandedChange = {},
        ) {}
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
    val haptics = LocalAppHaptics.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        items(SearchFilters, key = { it.name }) { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        haptics.tick()
                        onSelected(filter)
                    }
                },
                label = { Text(stringResource(filter.labelRes())) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
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
    onSearch: (String) -> Unit,
    onClearRecent: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onTrackClick: (Track) -> Unit,
    onTrackMore: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    when {
        state.submittedQuery == null -> if (state.recentQueries.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.search_idle_title),
                message = stringResource(R.string.search_idle_message),
                icon = Icons.Filled.Search,
            )
        } else {
            RecentSearches(
                recents = state.recentQueries,
                onPick = onSearch,
                onClear = onClearRecent,
                onRemove = onRemoveRecent,
            )
        }
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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        state = listState,
        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.s),
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
                    modifier = Modifier.fillMaxWidth().padding(Spacing.m),
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
}

private fun resultKey(result: SearchResult): String = when (result) {
    is SearchResult.TrackResult -> "t:" + result.track.id
    is SearchResult.PlaylistResult -> "p:" + result.playlist.url
    is SearchResult.ArtistResult -> "a:" + result.artist.url
}

@Composable
private fun PlaylistResultItem(playlist: RemotePlaylist, onClick: () -> Unit) {
    val haptics = LocalAppHaptics.current
    val count = playlist.trackCount?.toInt()?.let { pluralStringResource(R.plurals.common_track_count, it, it) }
    val subtitle = listOfNotNull(playlist.uploader, count).joinToString(" · ")
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable {
                haptics.click()
                onClick()
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Artwork(url = playlist.thumbnailUrl, modifier = Modifier.size(ArtworkSize.Row)) },
        headlineContent = {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
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
    val haptics = LocalAppHaptics.current
    val subscribers = artist.subscriberCount?.let { stringResource(R.string.common_artist_subscribers, formatCompactCount(it)) }
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable {
                haptics.click()
                onClick()
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Artwork(
                url = artist.avatarUrl,
                modifier = Modifier.size(ArtworkSize.Row),
                shape = CircleShape,
                placeholderIcon = Icons.Filled.Person,
            )
        },
        headlineContent = {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = subscribers?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

/** Historique affiché sur l'écran de recherche tant qu'aucune recherche n'a été lancée. */
@Composable
private fun RecentSearches(
    recents: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val haptics = LocalAppHaptics.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.s),
        ) {
            item(key = "recents-header") {
                SectionHeader(
                    title = stringResource(R.string.search_recent_title),
                    actionLabel = stringResource(R.string.search_clear_history),
                    onAction = {
                        haptics.confirm()
                        onClear()
                    },
                )
            }
            items(recents, key = { "r:$it" }) { entry ->
                SearchEntryRow(
                    text = entry,
                    icon = Icons.Filled.History,
                    onClick = { onPick(entry) },
                    trailing = {
                        IconButton(onClick = {
                            haptics.click()
                            onRemove(entry)
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_remove_recent))
                        }
                    },
                )
            }
        }
    }
}

/** Ligne d'historique ou de suggestion : icône tonale, texte, action finale. */
@Composable
private fun SearchEntryRow(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    ListItem(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable {
                haptics.click()
                onClick()
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { ToneIconCircle(icon = icon, tone = IconTone.Neutral, size = 40.dp) },
        headlineContent = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = trailing,
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
    val haptics = LocalAppHaptics.current
    val showRecents = query.isBlank()
    val entries = if (showRecents) recents else suggestions
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.s),
    ) {
        if (showRecents && recents.isNotEmpty()) {
            item(key = "recents-header") {
                SectionHeader(
                    title = stringResource(R.string.search_recent_title),
                    actionLabel = stringResource(R.string.search_clear_history),
                    onAction = {
                        haptics.confirm()
                        onClearRecent()
                    },
                )
            }
        }
        items(entries, key = { (if (showRecents) "r:" else "s:") + it }) { entry ->
            SearchEntryRow(
                text = entry,
                icon = if (showRecents) Icons.Filled.History else Icons.Filled.Search,
                onClick = { onPick(entry) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            haptics.click()
                            onFill(entry)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = stringResource(R.string.search_use_suggestion))
                        }
                        if (showRecents) {
                            IconButton(onClick = {
                                haptics.click()
                                onRemoveRecent(entry)
                            }) {
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
            onBack = {}, onQueryChange = {}, onSearch = {}, onFilterSelected = {}, onTrackClick = {}, onLoadMore = {},
            onRetry = {}, onClearRecent = {}, onOpenPlaylist = {}, onOpenArtist = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenRecentsPreview() {
    SpautifailleTheme(dynamicColor = false) {
        SearchScreen(
            state = SearchUiState(recentQueries = listOf("daft punk", "lofi hip hop", "stromae papaoutai")),
            onBack = {}, onQueryChange = {}, onSearch = {}, onFilterSelected = {}, onTrackClick = {}, onLoadMore = {},
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
            onBack = {}, onQueryChange = {}, onSearch = {}, onFilterSelected = {}, onTrackClick = {}, onLoadMore = {},
            onRetry = {}, onClearRecent = {}, onOpenPlaylist = {}, onOpenArtist = {},
        )
    }
}
