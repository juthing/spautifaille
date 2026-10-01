package com.spautifaille.ui.followed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Artist
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.TrackListPlaceholder
import com.spautifaille.ui.components.formatCompactCount
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

@Composable
fun FollowedArtistsScreenRoot(
    onBack: () -> Unit,
    onOpenArtist: (artistUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FollowedArtistsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FollowedArtistsScreen(
        state = state,
        onBack = onBack,
        onOpenArtist = onOpenArtist,
        onUnsubscribe = viewModel::unsubscribe,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedArtistsScreen(
    state: FollowedArtistsUiState,
    onBack: () -> Unit,
    onOpenArtist: (artistUrl: String) -> Unit,
    onUnsubscribe: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.followed_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
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
                state.artists.isEmpty() -> EmptyState(
                    title = stringResource(R.string.followed_empty_title),
                    message = stringResource(R.string.followed_empty_message),
                    icon = Icons.Filled.Person,
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(start = Spacing.s, top = Spacing.s, end = Spacing.s, bottom = ListBottomPadding),
                ) {
                    items(state.artists, key = { it.url }) { artist ->
                        ArtistRow(
                            artist = artist,
                            onClick = {
                                haptics.click()
                                onOpenArtist(artist.url)
                            },
                            onUnsubscribe = {
                                haptics.confirm()
                                onUnsubscribe(artist)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistRow(
    artist: Artist,
    onClick: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
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
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = artist.subscriberCount?.let { count ->
            { Text(stringResource(R.string.common_artist_subscribers, formatCompactCount(count))) }
        },
        trailingContent = {
            IconButton(onClick = onUnsubscribe) {
                Icon(
                    imageVector = Icons.Filled.PersonRemove,
                    contentDescription = stringResource(R.string.followed_unsubscribe, artist.name),
                )
            }
        },
    )
}

private val PreviewArtists = List(6) {
    Artist(url = "u$it", name = "Artiste suivi numéro $it", subscriberCount = 1_200L * (it + 1))
}

@Preview(showBackground = true)
@Composable
private fun FollowedArtistsScreenPreview() {
    SpautifailleTheme(dynamicColor = false) {
        FollowedArtistsScreen(
            state = FollowedArtistsUiState(isLoading = false, artists = PreviewArtists),
            onBack = {},
            onOpenArtist = {},
            onUnsubscribe = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FollowedArtistsScreenEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        FollowedArtistsScreen(
            state = FollowedArtistsUiState(isLoading = false),
            onBack = {},
            onOpenArtist = {},
            onUnsubscribe = {},
        )
    }
}
