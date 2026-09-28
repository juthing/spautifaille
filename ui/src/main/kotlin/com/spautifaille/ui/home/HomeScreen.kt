package com.spautifaille.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.SpautifailleTheme

@Composable
fun HomeScreenRoot(
    onOpenSettings: () -> Unit,
    onOpenLiked: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onRecentClick = viewModel::onRecentClick,
        onOpenLiked = onOpenLiked,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRecentClick: (Track) -> Unit,
    onOpenLiked: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(state.greeting.labelRes())) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "recent-title") { SectionTitle(stringResource(R.string.home_recent_title)) }
            item(key = "recent") {
                if (state.recent.isEmpty()) {
                    if (!state.isLoading) {
                        InlineHint(
                            icon = { Icon(Icons.Filled.History, contentDescription = null) },
                            title = stringResource(R.string.home_recent_empty_title),
                            message = stringResource(R.string.home_recent_empty_message),
                        )
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.recent, key = { it.id }) { track ->
                            RecentCard(track = track, onClick = { onRecentClick(track) })
                        }
                    }
                }
            }
            item(key = "liked") { LikedCard(onClick = onOpenLiked) }
            item(key = "discover-title") { SectionTitle(stringResource(R.string.home_discover_title)) }
            // TODO(J7) : recommandations « Découverte » (RecommendationSource).
            item(key = "discover") {
                InlineHint(
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                    title = stringResource(R.string.home_discover_soon_title),
                    message = stringResource(R.string.home_discover_soon_message),
                )
            }
        }
    }
}

private fun Greeting.labelRes(): Int = when (this) {
    Greeting.MORNING -> R.string.home_greeting_morning
    Greeting.AFTERNOON -> R.string.home_greeting_afternoon
    Greeting.EVENING -> R.string.home_greeting_evening
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun RecentCard(track: Track, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(144.dp)
            .clickable(onClick = onClick),
    ) {
        Artwork(url = track.thumbnailUrl, modifier = Modifier.size(144.dp), shape = MaterialTheme.shapes.large)
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LikedCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(R.string.home_liked_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.home_liked_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun InlineHint(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val PreviewRecent = List(6) { Track(id = "id$it", title = "Titre récent $it", artist = "Artiste $it") }

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SpautifailleTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(greeting = Greeting.EVENING, recent = PreviewRecent, isLoading = false),
            onRecentClick = {}, onOpenLiked = {}, onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(isLoading = false),
            onRecentClick = {}, onOpenLiked = {}, onOpenSettings = {},
        )
    }
}
