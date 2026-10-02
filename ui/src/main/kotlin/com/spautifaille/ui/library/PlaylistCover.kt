package com.spautifaille.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Playlist
import com.spautifaille.ui.R
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.SpautifailleTheme

/** Nom affiché : les playlists système sont localisées, les autres gardent leur nom. */
@Composable
internal fun Playlist.displayName(): String = when (id) {
    Playlist.DOWNLOADED_ID -> stringResource(R.string.lib_downloaded_playlist)
    else -> name
}

/**
 * Pochette d'une playlist :
 * - « Titres likés » : dégradé tonal du thème + cœur ;
 * - « Téléchargés » : dégradé tonal + icône de téléchargement terminé ;
 * - autres : mosaïque 2x2 si [mosaicUrls] compte au moins quatre images, sinon la pochette de la playlist.
 */
@Composable
internal fun PlaylistCover(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    mosaicUrls: List<String> = emptyList(),
) {
    when (playlist.id) {
        Playlist.LIKED_ID -> SpecialCover(
            icon = Icons.Filled.Favorite,
            startColor = MaterialTheme.colorScheme.primaryContainer,
            endColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = modifier,
            shape = shape,
        )
        Playlist.DOWNLOADED_ID -> SpecialCover(
            icon = Icons.Filled.DownloadDone,
            startColor = MaterialTheme.colorScheme.secondaryContainer,
            endColor = MaterialTheme.colorScheme.primaryContainer,
            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier,
            shape = shape,
        )
        else -> if (mosaicUrls.size >= MOSAIC_TILES) {
            Mosaic(urls = mosaicUrls, modifier = modifier, shape = shape)
        } else {
            Artwork(
                url = playlist.thumbnailUrl,
                modifier = modifier,
                shape = shape,
                placeholderIcon = Icons.AutoMirrored.Filled.QueueMusic,
            )
        }
    }
}

@Composable
private fun SpecialCover(
    icon: ImageVector,
    startColor: Color,
    endColor: Color,
    iconTint: Color,
    modifier: Modifier,
    shape: Shape,
) {
    val brush = remember(startColor, endColor) { Brush.linearGradient(listOf(startColor, endColor)) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.fillMaxSize(0.42f),
        )
    }
}

@Composable
private fun Mosaic(urls: List<String>, modifier: Modifier, shape: Shape) {
    Box(modifier.clip(shape)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                MosaicTile(urls[0], Modifier.weight(1f))
                MosaicTile(urls[1], Modifier.weight(1f))
            }
            Row(Modifier.weight(1f)) {
                MosaicTile(urls[2], Modifier.weight(1f))
                MosaicTile(urls[3], Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MosaicTile(url: String, modifier: Modifier) {
    Artwork(url = url, modifier = modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp))
}

private const val MOSAIC_TILES = 4

@Preview(showBackground = true)
@Composable
private fun PlaylistCoverPreview() {
    SpautifailleTheme(dynamicColor = false) {
        Row {
            val cover = Modifier.size(96.dp)
            PlaylistCover(Playlist(Playlist.LIKED_ID, "Titres likés", 3, null, true, 0, 0), cover)
            PlaylistCover(Playlist(Playlist.DOWNLOADED_ID, "Téléchargés", 3, null, true, 0, 0), cover)
            PlaylistCover(Playlist(5, "Road trip", 3, null, false, 0, 0), cover)
        }
    }
}
