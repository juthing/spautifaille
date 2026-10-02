package com.spautifaille.ui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.ArtistDetails
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.LoadingState
import com.spautifaille.ui.components.SectionHeader
import com.spautifaille.ui.components.TrackActionsSheet
import com.spautifaille.ui.components.TrackListItem
import com.spautifaille.ui.components.formatCompactCount
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

@Immutable
data class ArtistActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onToggleSubscription: () -> Unit = {},
    val onPlayFrom: (Int) -> Unit = {},
    val onPlayAll: (shuffle: Boolean) -> Unit = {},
    val onOpenRemotePlaylist: (String) -> Unit = {},
)

/**
 * Page d'un artiste / d'une chaîne.
 * Argument de navigation lu par le ViewModel : `SavedStateHandle["url"]` (String).
 */
@Composable
fun ArtistRoute(
    onBack: () -> Unit,
    onOpenRemotePlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack, onOpenRemotePlaylist) {
        ArtistActions(
            onBack = onBack,
            onRetry = viewModel::load,
            onToggleSubscription = viewModel::toggleSubscription,
            onPlayFrom = viewModel::playFrom,
            onPlayAll = viewModel::playAll,
            onOpenRemotePlaylist = onOpenRemotePlaylist,
        )
    }
    ArtistScreen(state, actions, modifier)
}

/** Taille de l'avatar dans l'en-tête. */
private val HeaderAvatarSize = 128.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    actions: ArtistActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val name = (state.status as? ArtistStatus.Content)?.details?.artist?.name.orEmpty()
    // Le nom est déjà dans l'en-tête : il n'apparaît dans la barre qu'une fois l'en-tête sorti de l'écran.
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var actionsTrack by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { if (showTitle) Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        when (val status = state.status) {
            ArtistStatus.Loading -> LoadingState(Modifier.padding(innerPadding))
            is ArtistStatus.Error -> ErrorState(
                title = stringResource(R.string.misc_artist_load_failed),
                message = stringResource(status.error.toMessage()),
                onRetry = actions.onRetry,
                modifier = Modifier.padding(innerPadding),
            )
            is ArtistStatus.Content -> ArtistContent(
                details = status.details,
                isSubscribed = state.isSubscribed,
                actions = actions,
                listState = listState,
                innerPadding = innerPadding,
                onTrackMore = { actionsTrack = it },
            )
        }
    }

    actionsTrack?.let { track ->
        TrackActionsSheet(track = track, onDismiss = { actionsTrack = null })
    }
}

@Composable
private fun ArtistContent(
    details: ArtistDetails,
    isSubscribed: Boolean,
    actions: ArtistActions,
    listState: androidx.compose.foundation.lazy.LazyListState,
    innerPadding: PaddingValues,
    onTrackMore: (Track) -> Unit,
) {
    val haptics = LocalAppHaptics.current
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // La barre du haut est transparente : l'en-tête passe derrière elle (son propre padding la compense).
        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + ListBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "header") {
            ArtistHeader(
                details = details,
                isSubscribed = isSubscribed,
                actions = actions,
                topInset = innerPadding.calculateTopPadding(),
            )
        }

        if (details.tracks.isNotEmpty()) {
            item(key = "tracks-title") {
                SectionHeader(
                    title = stringResource(R.string.misc_artist_songs),
                    modifier = Modifier.widthIn(max = ContentMaxWidth).padding(top = Spacing.m),
                )
            }
            itemsIndexed(details.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                TrackListItem(
                    track = track,
                    onClick = { actions.onPlayFrom(index) },
                    onMoreClick = { onTrackMore(track) },
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.s),
                )
            }
        }
        if (details.playlists.isNotEmpty()) {
            item(key = "playlists-title") {
                SectionHeader(
                    title = stringResource(R.string.misc_artist_albums),
                    modifier = Modifier.widthIn(max = ContentMaxWidth).padding(top = Spacing.m),
                )
            }
            item(key = "playlists-row") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    items(details.playlists, key = { it.url }) { playlist ->
                        RemotePlaylistCard(playlist) {
                            haptics.click()
                            actions.onOpenRemotePlaylist(playlist.url)
                        }
                    }
                }
            }
        }
        details.description?.takeIf { it.isNotBlank() }?.let { description ->
            item(key = "about") {
                Column(Modifier.widthIn(max = ContentMaxWidth).padding(top = Spacing.m)) {
                    SectionHeader(title = stringResource(R.string.misc_artist_about))
                    ExpandableDescription(description)
                }
            }
        }
        if (details.tracks.isEmpty() && details.playlists.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.misc_artist_empty_title),
                    message = stringResource(R.string.misc_artist_empty_message),
                    modifier = Modifier.height(280.dp),
                )
            }
        }
    }
}

/** En-tête : dégradé tonal (ou bannière estompée), avatar, nom, abonnés, puis Lecture / Aléatoire / S'abonner. */
@Composable
private fun ArtistHeader(
    details: ArtistDetails,
    isSubscribed: Boolean,
    actions: ArtistActions,
    topInset: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val artist = details.artist
    val colors = MaterialTheme.colorScheme
    val hasTracks = details.tracks.isNotEmpty()
    Box(modifier.fillMaxWidth()) {
        if (details.bannerUrl != null) {
            Artwork(
                url = details.bannerUrl,
                modifier = Modifier.matchParentSize(),
                shape = RectangleShape,
                placeholderIcon = Icons.Filled.Person,
            )
        }
        // Voile : teinte du thème en haut, fondu vers la surface en bas pour raccorder la liste.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.primaryContainer.copy(alpha = if (details.bannerUrl != null) 0.72f else 1f),
                            colors.surface,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset + Spacing.s, bottom = Spacing.l)
                .padding(horizontal = ScreenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Artwork(
                url = artist.avatarUrl,
                modifier = Modifier.size(HeaderAvatarSize),
                shape = CircleShape,
                contentDescription = stringResource(R.string.misc_artist_avatar_description, artist.name),
                placeholderIcon = Icons.Filled.Person,
            )
            Text(
                text = artist.name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = Spacing.s)
                    .semantics { heading() },
            )
            artist.subscriberCount?.let { count ->
                Text(
                    text = stringResource(R.string.common_artist_subscribers, formatCompactCount(count)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(top = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Button(
                    onClick = {
                        haptics.click()
                        actions.onPlayAll(false)
                    },
                    enabled = hasTracks,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.common_action_play), Modifier.padding(start = Spacing.s))
                }
                FilledTonalButton(
                    onClick = {
                        haptics.click()
                        actions.onPlayAll(true)
                    },
                    enabled = hasTracks,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.misc_action_shuffle), Modifier.padding(start = Spacing.s))
                }
            }
            val toggle = {
                haptics.toggle(!isSubscribed)
                actions.onToggleSubscription()
            }
            if (isSubscribed) {
                FilledTonalButton(onClick = toggle) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.misc_subscribed), Modifier.padding(start = Spacing.s))
                }
            } else {
                OutlinedButton(onClick = toggle) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.misc_subscribe), Modifier.padding(start = Spacing.s))
                }
            }
        }
    }
}

@Composable
private fun ExpandableDescription(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val hint = stringResource(R.string.misc_description_expand)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = hint) { expanded = !expanded }
            .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
    )
}

/** Carte du carrousel d'albums / playlists : pochette, nom, nombre de titres. */
@Composable
private fun RemotePlaylistCard(playlist: RemotePlaylist, onClick: () -> Unit) {
    val count = playlist.trackCount?.toInt()?.let { pluralStringResource(R.plurals.common_track_count, it, it) }
    Column(
        Modifier
            .width(ArtworkSize.Card)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Artwork(url = playlist.thumbnailUrl, modifier = Modifier.size(ArtworkSize.Card), shape = MaterialTheme.shapes.medium)
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                text = count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// region Previews

private fun previewDetails() = ArtistDetails(
    artist = Artist("https://youtube.com/@a", "Artiste Exemple", null, 1_250_000),
    description = "Une description assez longue de l'artiste qui peut être dépliée en touchant le texte pour tout lire.",
    bannerUrl = null,
    tracks = (1..6).map { Track("id$it", "Titre numéro $it", "Artiste Exemple", durationMs = 200_000L) },
    playlists = (1..4).map { RemotePlaylist("https://youtube.com/playlist?list=$it", "Album numéro $it", null, null, 12) },
)

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ArtistPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ArtistScreen(ArtistUiState(ArtistStatus.Content(previewDetails()), isSubscribed = false), ArtistActions())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ArtistSubscribedPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ArtistScreen(ArtistUiState(ArtistStatus.Content(previewDetails()), isSubscribed = true), ArtistActions())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ArtistEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ArtistScreen(
            ArtistUiState(ArtistStatus.Content(previewDetails().copy(tracks = emptyList(), playlists = emptyList()))),
            ArtistActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ArtistErrorPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ArtistScreen(ArtistUiState(ArtistStatus.Error(AppError.Network)), ArtistActions())
    }
}

// endregion
