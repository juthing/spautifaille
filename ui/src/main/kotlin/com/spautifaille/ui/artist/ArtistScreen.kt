package com.spautifaille.ui.artist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.ArtistDetails
import com.spautifaille.domain.model.RemotePlaylist
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.library.LibraryArtwork
import com.spautifaille.ui.library.LibraryContentMaxWidth
import com.spautifaille.ui.library.LibraryEmptyState
import com.spautifaille.ui.library.LibraryTrackRow
import com.spautifaille.ui.library.formatCompactCount
import com.spautifaille.ui.library.libraryMessage

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    actions: ArtistActions,
    modifier: Modifier = Modifier,
) {
    val title = (state.status as? ArtistStatus.Content)?.details?.artist?.name.orEmpty()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lib_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            when (val status = state.status) {
                ArtistStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ArtistStatus.Error -> LibraryEmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(R.string.lib_artist_load_failed),
                    body = stringResource(status.error.libraryMessage()),
                    actionLabel = stringResource(R.string.lib_retry),
                    onAction = actions.onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
                is ArtistStatus.Content -> ArtistContent(status.details, state.isSubscribed, actions)
            }
        }
    }
}

@Composable
private fun ArtistContent(details: ArtistDetails, isSubscribed: Boolean, actions: ArtistActions) {
    LazyColumn(Modifier.widthIn(max = LibraryContentMaxWidth).fillMaxSize()) {
        item(key = "header") { ArtistHeader(details, isSubscribed, actions) }

        if (details.tracks.isNotEmpty()) {
            item(key = "tracks-title") { SectionTitle(stringResource(R.string.lib_artist_tracks)) }
            itemsIndexed(details.tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                LibraryTrackRow(track, onClick = { actions.onPlayFrom(index) })
            }
        }
        if (details.playlists.isNotEmpty()) {
            item(key = "playlists-title") { SectionTitle(stringResource(R.string.lib_artist_playlists)) }
            item(key = "playlists-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(details.playlists, key = { it.url }) { playlist ->
                        RemotePlaylistCard(playlist) { actions.onOpenRemotePlaylist(playlist.url) }
                    }
                }
            }
        }
        if (details.tracks.isEmpty() && details.playlists.isEmpty()) {
            item(key = "empty") {
                LibraryEmptyState(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.lib_artist_empty_title),
                    body = stringResource(R.string.lib_artist_empty_body),
                )
            }
        }
        item(key = "bottom-space") { Box(Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun ArtistHeader(details: ArtistDetails, isSubscribed: Boolean, actions: ArtistActions) {
    val artist = details.artist
    Column {
        if (details.bannerUrl != null) {
            LibraryArtwork(
                url = details.bannerUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(BANNER_ASPECT_RATIO),
                shape = RectangleShape,
                fallbackIcon = Icons.Filled.Person,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LibraryArtwork(
                url = artist.avatarUrl,
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                fallbackIcon = Icons.Filled.Person,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                artist.subscriberCount?.let { count ->
                    Text(
                        text = stringResource(R.string.lib_subscribers, formatCompactCount(count)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isSubscribed) {
                FilledTonalButton(onClick = actions.onToggleSubscription) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.lib_subscribed), Modifier.padding(start = 8.dp))
                }
            } else {
                Button(onClick = actions.onToggleSubscription) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.lib_subscribe), Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(
                onClick = { actions.onPlayAll(true) },
                enabled = details.tracks.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.lib_shuffle), Modifier.padding(start = 8.dp))
            }
        }
        details.description?.takeIf { it.isNotBlank() }?.let { ExpandableDescription(it) }
    }
}

@Composable
private fun ExpandableDescription(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun RemotePlaylistCard(playlist: RemotePlaylist, onClick: () -> Unit) {
    Column(
        Modifier
            .width(148.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LibraryArtwork(
            url = playlist.thumbnailUrl,
            modifier = Modifier.size(148.dp),
            shape = MaterialTheme.shapes.medium,
        )
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val BANNER_ASPECT_RATIO = 16f / 5f

// region Previews

private fun previewDetails() = ArtistDetails(
    artist = Artist("https://youtube.com/@a", "Artiste Exemple", null, 1_250_000),
    description = "Une description assez longue de l'artiste qui peut être dépliée en touchant le texte pour tout lire.",
    bannerUrl = null,
    tracks = (1..6).map { Track("id$it", "Titre numéro $it", "Artiste Exemple", durationMs = 200_000L) },
    playlists = (1..4).map { RemotePlaylist("https://youtube.com/playlist?list=$it", "Album numéro $it", null, null, 12) },
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ArtistPreview() {
    MaterialTheme {
        ArtistScreen(ArtistUiState(ArtistStatus.Content(previewDetails()), isSubscribed = false), ArtistActions())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ArtistSubscribedPreview() {
    MaterialTheme {
        ArtistScreen(ArtistUiState(ArtistStatus.Content(previewDetails()), isSubscribed = true), ArtistActions())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ArtistErrorPreview() {
    MaterialTheme {
        ArtistScreen(ArtistUiState(ArtistStatus.Error(AppError.Network)), ArtistActions())
    }
}

// endregion
