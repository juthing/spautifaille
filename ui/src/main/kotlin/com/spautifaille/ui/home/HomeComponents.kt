package com.spautifaille.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

internal val ShortcutHeight: Dp = 56.dp
private val ArtistAvatarSize: Dp = 88.dp
private val PlayButtonSize: Dp = 40.dp
private val SearchFieldHeight: Dp = 56.dp

/**
 * Barre de recherche de l'Accueil : champ tonal en pilule, non éditable ; un appui ouvre l'écran de
 * recherche (qui prend le focus et ouvre le clavier).
 */
@Composable
internal fun HomeSearchField(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalAppHaptics.current
    Surface(
        onClick = {
            haptics.click()
            onClick()
        },
        modifier = modifier
            .padding(horizontal = ScreenHorizontalPadding)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { role = Role.Button },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .height(SearchFieldHeight)
                .padding(horizontal = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.search_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Raccourci compact : pochette carrée à gauche, nom à droite, fond tonal. */
@Composable
internal fun ShortcutCard(
    title: String,
    thumbnailUrl: String?,
    isLiked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    Surface(
        onClick = {
            haptics.click()
            onClick()
        },
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.height(ShortcutHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLiked) {
                LikedArtwork(Modifier.size(ShortcutHeight))
            } else {
                Artwork(
                    url = thumbnailUrl,
                    modifier = Modifier.size(ShortcutHeight),
                    shape = RectangleShape,
                    placeholderIcon = Icons.Filled.LibraryMusic,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.s + Spacing.xs),
            )
        }
    }
}

/** Pochette de « Titres likés » : dégradé aux couleurs du thème et cœur. */
@Composable
private fun LikedArtwork(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier.fillMaxSize(0.45f),
        )
    }
}

/**
 * Carte de titre pour un carrousel : pochette arrondie, titre, artiste. Appui = lecture ([onClick]), appui long =
 * actions ([onLongClick]). Avec [onPlayClick], un bouton lecture rond est posé sur la pochette.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackCard(
    track: Track,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayClick: (() -> Unit)? = null,
) {
    val haptics = LocalAppHaptics.current
    Column(
        modifier = modifier
            .width(size)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClickLabel = stringResource(R.string.common_action_play),
                onClick = {
                    haptics.click()
                    onClick()
                },
                onLongClickLabel = stringResource(R.string.common_action_more),
                onLongClick = {
                    haptics.longPress()
                    onLongClick()
                },
            ),
    ) {
        Box {
            Artwork(url = track.thumbnailUrl, modifier = Modifier.size(size), shape = MaterialTheme.shapes.large)
            if (onPlayClick != null) {
                FilledIconButton(
                    onClick = {
                        haptics.click()
                        onPlayClick()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.s)
                        .size(PlayButtonSize),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.home_play_track, track.title),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = Spacing.xs)
                .padding(bottom = Spacing.xs),
        )
    }
}

/** Avatar rond d'un artiste suivi, nom centré dessous. */
@Composable
internal fun ArtistAvatarItem(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalAppHaptics.current
    Column(
        modifier = modifier
            .width(ArtistAvatarSize + Spacing.m)
            .clip(MaterialTheme.shapes.large)
            .clickable {
                haptics.click()
                onClick()
            }
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            url = artist.avatarUrl,
            modifier = Modifier.size(ArtistAvatarSize),
            shape = CircleShape,
            placeholderIcon = Icons.Filled.Person,
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
    }
}

/** Invitation du premier lancement : rien d'écouté ni de suivi. */
@Composable
internal fun WelcomeCard(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalAppHaptics.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Headphones,
                contentDescription = null,
                modifier = Modifier.size(Spacing.xxl),
            )
            Spacer(Modifier.height(Spacing.m))
            Text(
                text = stringResource(R.string.home_welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = stringResource(R.string.home_welcome_message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.l))
            Button(onClick = {
                haptics.click()
                onSearch()
            }) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.home_welcome_action), modifier = Modifier.padding(start = Spacing.s))
            }
        }
    }
}

/** Bandeau discret (hors ligne, suggestions vides ou en erreur). */
@Composable
internal fun InlineHint(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(Spacing.xl), contentAlignment = Alignment.Center) { icon() }
            Column(modifier = Modifier.padding(start = Spacing.m)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                action?.invoke()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeComponentsPreview() {
    SpautifailleTheme(dynamicColor = false) {
        val track = Track(id = "a", title = "Un titre assez long pour déborder", artist = "Artiste")
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.padding(Spacing.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ShortcutCard("Titres likés", null, isLiked = true, onClick = {}, modifier = Modifier.weight(1f))
                ShortcutCard("Road trip de l'été 2025", null, isLiked = false, onClick = {}, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                TrackCard(track, ArtworkSize.CardLarge, onClick = {}, onLongClick = {}, onPlayClick = {})
                ArtistAvatarItem(Artist(url = "u", name = "Un artiste"), onClick = {})
            }
            WelcomeCard(onSearch = {})
        }
    }
}
