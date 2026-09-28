package com.spautifaille.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.theme.SpautifailleTheme

/** Ligne de titre : pochette, titre, « artiste · durée » et menu d'actions. [isCurrent] met le titre en évidence. */
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
) {
    val subtitle = track.durationMs?.let { stringResource(R.string.common_track_subtitle, track.artist, formatDuration(it)) }
        ?: track.artist
    ListItem(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            headlineColor = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            supportingColor = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        leadingContent = { Artwork(url = track.thumbnailUrl, modifier = Modifier.size(52.dp)) },
        headlineContent = {
            Text(
                text = track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            )
        },
        supportingContent = { Text(text = subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_action_more))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun TrackListItemPreview() {
    SpautifailleTheme(dynamicColor = false) {
        val track = Track(id = "a", title = "Un très long titre de morceau qui déborde de la ligne", artist = "Artiste", durationMs = 215_000)
        androidx.compose.foundation.layout.Column {
            TrackListItem(track, onClick = {}, onMoreClick = {})
            TrackListItem(track, onClick = {}, onMoreClick = {}, isCurrent = true)
        }
    }
}
