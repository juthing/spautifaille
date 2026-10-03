package com.spautifaille.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.components.SectionHeader
import com.spautifaille.ui.components.ShimmerBox
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.discovery.DiscoveryActions
import com.spautifaille.ui.discovery.DiscoveryStatus
import com.spautifaille.ui.discovery.DiscoveryUiState
import com.spautifaille.ui.discovery.DiscoveryViewModel
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.SectionSpacing
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

@Composable
fun HomeScreenRoot(
    onOpenLiked: () -> Unit,
    modifier: Modifier = Modifier,
    /** Ouvre une playlist locale (raccourcis). */
    onOpenPlaylist: (playlistId: Long) -> Unit = {},
    /** Ouvre la liste complète (`DiscoveryRoute`). */
    onOpenDiscovery: () -> Unit = {},
    /** Ouvre la page d'un artiste (`ArtistRoute`). */
    onOpenArtist: (artistUrl: String) -> Unit = {},
    /** Ouvre l'écran « Tes artistes » (`FollowedArtistsRoute`). */
    onOpenFollowedArtists: () -> Unit = {},
    /** Ouvre l'historique (`HistoryRoute`). */
    onOpenHistory: () -> Unit = {},
    /** Ouvre l'écran de recherche (`SearchRoute`). */
    onOpenSearch: () -> Unit = {},
    /** Actions de la barre d'application (avatar du compte YouTube). */
    topBarActions: @Composable RowScope.() -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Hors ligne (ou pas encore connu) : le ViewModel de découverte n'est pas créé, donc aucun calcul réseau.
    val discovery = if (!state.isLoading && state.isOnline) discoveryState() else null
    HomeScreen(
        state = state,
        onRecentClick = viewModel::onRecentClick,
        onOpenLiked = onOpenLiked,
        modifier = modifier,
        discovery = discovery?.first ?: DiscoveryUiState(),
        discoveryActions = discovery?.second ?: DiscoveryActions(),
        onOpenPlaylist = onOpenPlaylist,
        onOpenDiscovery = onOpenDiscovery,
        onOpenArtist = onOpenArtist,
        onOpenFollowedArtists = onOpenFollowedArtists,
        onOpenHistory = onOpenHistory,
        onOpenSearch = onOpenSearch,
        topBarActions = topBarActions,
    )
}

@Composable
private fun discoveryState(viewModel: DiscoveryViewModel = hiltViewModel()): Pair<DiscoveryUiState, DiscoveryActions> {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        DiscoveryActions(
            onRefresh = viewModel::refresh,
            onPlayAll = viewModel::playAll,
            onPlayFrom = viewModel::playFrom,
        )
    }
    return state to actions
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRecentClick: (Track) -> Unit,
    onOpenLiked: () -> Unit,
    modifier: Modifier = Modifier,
    discovery: DiscoveryUiState = DiscoveryUiState(isLoading = false),
    discoveryActions: DiscoveryActions = DiscoveryActions(),
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenDiscovery: () -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenFollowedArtists: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    var actionsTrack by remember { mutableStateOf<Track?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Même barre que Bibliothèque et Réglages : la salutation en est le titre.
        topBar = {
            TopAppBar(
                title = { Text(stringResource(state.greeting.labelRes())) },
                actions = topBarActions,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Encoches / barres latérales (paysage) ; les marges haut et bas sont dans le contentPadding.
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + Spacing.s,
                    bottom = padding.calculateBottomPadding() + ListBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        HomeSearchField(onClick = onOpenSearch)
                        if (state.isLoading) {
                            ShortcutsPlaceholder()
                        } else {
                            Shortcuts(
                                shortcuts = state.shortcuts,
                                onOpenLiked = onOpenLiked,
                                onOpenPlaylist = onOpenPlaylist,
                            )
                        }
                    }
                }
                if (state.isLoading) {
                    item(key = "loading") { CarouselPlaceholder() }
                } else {
                    if (state.isFirstLaunch) {
                        item(key = "welcome") { WelcomeCard(onSearch = onOpenSearch) }
                    }
                    if (state.recent.isNotEmpty()) {
                        item(key = "recent") {
                            RecentSection(
                                tracks = state.recent,
                                onClick = onRecentClick,
                                onLongClick = { actionsTrack = it },
                                onSeeAll = onOpenHistory,
                            )
                        }
                    }
                    if (state.artists.isNotEmpty()) {
                        item(key = "artists") {
                            ArtistsSection(
                                artists = state.artists,
                                onOpenArtist = onOpenArtist,
                                onSeeAll = onOpenFollowedArtists,
                            )
                        }
                    }
                    if (!state.isOnline) {
                        item(key = "offline") {
                            InlineHint(
                                icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
                                title = stringResource(R.string.home_offline_title),
                                message = stringResource(R.string.home_offline_message),
                            )
                        }
                    } else if (!(state.isFirstLaunch && discovery.status == DiscoveryStatus.EMPTY)) {
                        discoverySection(
                            state = discovery,
                            actions = discoveryActions,
                            onSeeAll = onOpenDiscovery,
                            onTrackLongClick = { actionsTrack = it },
                        )
                    }
                }
            }
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            onDismiss = { actionsTrack = null },
            onGoToArtist = { url ->
                actionsTrack = null
                onOpenArtist(url)
            },
        )
    }
}

private fun Greeting.labelRes(): Int = when (this) {
    Greeting.MORNING -> R.string.home_greeting_morning
    Greeting.AFTERNOON -> R.string.home_greeting_afternoon
    Greeting.EVENING -> R.string.home_greeting_evening
}

/** Grille 2 colonnes de raccourcis (« Titres likés » puis playlists). */
@Composable
private fun Shortcuts(
    shortcuts: List<HomeShortcut>,
    onOpenLiked: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        shortcuts.chunked(SHORTCUT_COLUMNS).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                rowItems.forEach { shortcut ->
                    ShortcutCard(
                        title = if (shortcut.isLiked) stringResource(R.string.home_liked_title) else shortcut.name,
                        thumbnailUrl = shortcut.thumbnailUrl,
                        isLiked = shortcut.isLiked,
                        onClick = { if (shortcut.isLiked) onOpenLiked() else onOpenPlaylist(shortcut.playlistId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Dernière ligne incomplète : la colonne vide garde la largeur des cartes.
                repeat(SHORTCUT_COLUMNS - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val SHORTCUT_COLUMNS = 2

@Composable
private fun RecentSection(
    tracks: List<Track>,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            title = stringResource(R.string.home_recently_played_title),
            actionLabel = stringResource(R.string.home_see_all),
            onAction = onSeeAll,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(tracks, key = { it.id }) { track ->
                TrackCard(
                    track = track,
                    size = ArtworkSize.Card,
                    onClick = { onClick(track) },
                    onLongClick = { onLongClick(track) },
                )
            }
        }
    }
}

@Composable
private fun ArtistsSection(
    artists: List<Artist>,
    onOpenArtist: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            title = stringResource(R.string.home_artists_title),
            actionLabel = stringResource(R.string.home_see_all),
            onAction = onSeeAll,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            items(artists, key = { it.url }) { artist ->
                ArtistAvatarItem(artist = artist, onClick = { onOpenArtist(artist.url) })
            }
        }
    }
}

@Composable
private fun ShortcutsPlaceholder() {
    Column(
        modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                repeat(SHORTCUT_COLUMNS) {
                    ShimmerBox(
                        modifier = Modifier
                            .weight(1f)
                            .height(ShortcutHeight),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun CarouselPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        ShimmerBox(
            modifier = Modifier
                .padding(horizontal = ScreenHorizontalPadding)
                .height(24.dp)
                .fillMaxWidth(0.5f),
        )
        Row(
            modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            repeat(2) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    ShimmerBox(Modifier.size(ArtworkSize.Card), shape = MaterialTheme.shapes.large)
                    ShimmerBox(Modifier.height(14.dp).fillMaxWidth(0.7f))
                }
            }
        }
    }
}

private val PreviewRecent = List(6) { Track(id = "id$it", title = "Titre récent $it", artist = "Artiste $it") }
private val PreviewArtists = List(5) { Artist(url = "u$it", name = "Artiste suivi $it") }
private val PreviewShortcuts = listOf(
    HomeShortcut(1L, "Titres likés", null, isLiked = true),
    HomeShortcut(2L, "Road trip", null, isLiked = false),
    HomeShortcut(3L, "Concentration profonde du matin", null, isLiked = false),
    HomeShortcut(4L, "Soirée", null, isLiked = false),
    HomeShortcut(5L, "Sport", null, isLiked = false),
)

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeScreenPreview() {
    SpautifailleTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(
                greeting = Greeting.EVENING,
                recent = PreviewRecent,
                shortcuts = PreviewShortcuts,
                artists = PreviewArtists,
                isLoading = false,
            ),
            onRecentClick = {},
            onOpenLiked = {},
            discovery = DiscoveryUiState(tracks = PreviewRecent.map { it.copy(id = "d${it.id}") }, isLoading = false),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenFirstLaunchPreview() {
    SpautifailleTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(
                shortcuts = PreviewShortcuts.take(1),
                isLoading = false,
            ),
            onRecentClick = {},
            onOpenLiked = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenOfflinePreview() {
    SpautifailleTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(
                recent = PreviewRecent,
                shortcuts = PreviewShortcuts.take(3),
                isOnline = false,
                isLoading = false,
            ),
            onRecentClick = {},
            onOpenLiked = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenLoadingPreview() {
    SpautifailleTheme(dynamicColor = false) {
        HomeScreen(state = HomeUiState(), onRecentClick = {}, onOpenLiked = {})
    }
}
