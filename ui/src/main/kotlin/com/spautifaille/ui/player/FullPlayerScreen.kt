package com.spautifaille.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.QueueItem
import com.spautifaille.domain.player.RepeatMode
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.ui.R
import com.spautifaille.ui.components.AddToPlaylistSheet
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.formatDuration
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class PlayerSheet { None, Queue, Speed, Sleep, Playlist }

private val WideLayoutMinWidth = 600.dp

/**
 * Lecteur plein écran. Sans état : tout vient de [state] / [positionProvider] et remonte par [actions].
 * [positionProvider] est lu uniquement dans la barre de progression, qui se recompose seule à ~4 Hz.
 */
@Composable
fun FullPlayerScreen(
    state: PlayerState,
    positionProvider: () -> PlaybackPosition,
    actions: PlayerActions,
    onCollapse: () -> Unit,
    onOpenArtist: (artistUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    transition: PlayerTransition? = null,
    sleepRemainingProvider: () -> Long? = { null },
) {
    val track = state.currentTrack ?: return
    var sheet by rememberSaveable { mutableStateOf(PlayerSheet.None) }

    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier = modifier
            .fillMaxSize()
            .playerSharedBounds(PlayerSharedKeys.Container, transition, shape),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surface),
                    ),
                )
                .safeDrawingPadding(),
        ) {
            val wide = maxWidth >= WideLayoutMinWidth && maxWidth > maxHeight
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                PlayerTopBar(onCollapse = onCollapse)
                if (wide) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerArtwork(
                            track = track,
                            transition = transition,
                            modifier = Modifier.weight(1f).fillMaxSize().padding(bottom = 16.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PlayerBody(
                                state, track, positionProvider, sleepRemainingProvider, actions, onOpenArtist, transition,
                            ) { sheet = it }
                        }
                    }
                } else {
                    PlayerArtwork(
                        track = track,
                        transition = transition,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerBody(
                            state, track, positionProvider, sleepRemainingProvider, actions, onOpenArtist, transition,
                        ) { sheet = it }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    when (sheet) {
        PlayerSheet.None -> Unit
        PlayerSheet.Queue -> QueueSheet(state = state, actions = actions, onDismiss = { sheet = PlayerSheet.None })
        PlayerSheet.Speed -> SpeedSheet(
            currentSpeed = state.speed,
            onSelect = actions.onSetSpeed,
            onDismiss = { sheet = PlayerSheet.None },
        )
        PlayerSheet.Sleep -> SleepTimerSheet(
            timer = state.sleepTimer,
            remainingProvider = sleepRemainingProvider,
            onSelectMinutes = actions.onSleepMinutes,
            onEndOfTrack = actions.onSleepEndOfTrack,
            onCancel = actions.onCancelSleep,
            onDismiss = { sheet = PlayerSheet.None },
        )
        PlayerSheet.Playlist -> AddToPlaylistSheet(tracks = listOf(track), onDismiss = { sheet = PlayerSheet.None })
    }
}

@Composable
private fun PlayerBody(
    state: PlayerState,
    track: Track,
    positionProvider: () -> PlaybackPosition,
    sleepRemainingProvider: () -> Long?,
    actions: PlayerActions,
    onOpenArtist: (String) -> Unit,
    transition: PlayerTransition?,
    openSheet: (PlayerSheet) -> Unit,
) {
    TrackInfo(
        track = track,
        isLiked = state.isCurrentLiked,
        onToggleLike = actions.onToggleLike,
        onArtistClick = track.artistUrl?.let { url -> { onOpenArtist(url) } },
        transition = transition,
    )
    SeekBar(
        positionProvider = positionProvider,
        fallbackDurationMs = state.durationMs,
        onSeek = actions.onSeek,
    )
    PlayerControls(state = state, actions = actions)
    SecondaryActions(
        state = state,
        sleepRemainingProvider = sleepRemainingProvider,
        onSpeed = { openSheet(PlayerSheet.Speed) },
        onSleep = { openSheet(PlayerSheet.Sleep) },
        onQueue = { openSheet(PlayerSheet.Queue) },
        onAddToPlaylist = { openSheet(PlayerSheet.Playlist) },
    )
}

@Composable
private fun PlayerTopBar(onCollapse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_collapse))
        }
        Text(
            text = stringResource(R.string.player_now_playing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun PlayerArtwork(track: Track, transition: PlayerTransition?, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val shape = MaterialTheme.shapes.extraLarge
        Artwork(
            url = track.thumbnailUrl,
            contentDescription = track.title,
            shape = shape,
            modifier = Modifier
                .aspectRatio(1f)
                .playerSharedElement(PlayerSharedKeys.Artwork, transition)
                .shadow(elevation = 12.dp, shape = shape, clip = false),
        )
    }
}

@Composable
private fun TrackInfo(
    track: Track,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onArtistClick: (() -> Unit)?,
    transition: PlayerTransition?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.playerSharedBounds(PlayerSharedKeys.Title, transition),
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = if (onArtistClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onArtistClick != null) Modifier.clickable(onClick = onArtistClick) else Modifier,
            )
        }
        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(if (isLiked) R.string.common_action_unlike else R.string.common_action_like),
                tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Barre de progression. L'état de glissement est local pour ne pas lutter contre les mises à jour de position ;
 * après le relâchement, la valeur visée est conservée brièvement jusqu'à ce que le lecteur l'ait rattrapée.
 */
@Composable
private fun SeekBar(
    positionProvider: () -> PlaybackPosition,
    fallbackDurationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val position = positionProvider()
    val duration = if (position.durationMs > 0) position.durationMs else fallbackDurationMs
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(pendingSeekMs) {
        if (pendingSeekMs != null) {
            delay(1_000)
            pendingSeekMs = null
        }
    }
    LaunchedEffect(position.positionMs) {
        val pending = pendingSeekMs
        if (pending != null && abs(position.positionMs - pending) < 800) pendingSeekMs = null
    }

    val fraction = when {
        duration <= 0 -> 0f
        dragFraction != null -> dragFraction!!
        pendingSeekMs != null -> pendingSeekMs!!.toFloat() / duration
        else -> position.positionMs.toFloat() / duration
    }.coerceIn(0f, 1f)
    val shownMs = (fraction * duration).toLong()

    Column {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                val target = dragFraction
                if (target != null && duration > 0) {
                    val ms = (target * duration).toLong()
                    pendingSeekMs = ms
                    onSeek(ms)
                }
                dragFraction = null
            },
            enabled = duration > 0,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(shownMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "-" + formatDuration((duration - shownMs).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerState, actions: PlayerActions) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onToggleShuffle) {
            Icon(
                Icons.Filled.Shuffle,
                contentDescription = stringResource(if (state.shuffleEnabled) R.string.player_shuffle_on else R.string.player_shuffle_off),
                tint = if (state.shuffleEnabled) active else inactive,
            )
        }
        IconButton(onClick = actions.onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.common_action_previous), modifier = Modifier.size(36.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            FilledIconButton(onClick = actions.onPlayPause, modifier = Modifier.size(68.dp)) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.common_action_pause else R.string.common_action_play),
                    modifier = Modifier.size(40.dp),
                )
            }
            if (state.isBuffering && state.playWhenReady) {
                CircularProgressIndicator(modifier = Modifier.size(80.dp), strokeWidth = 3.dp)
            }
        }
        IconButton(onClick = actions.onNext, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.common_action_next), modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = actions.onCycleRepeat) {
            val (icon, description) = when (state.repeatMode) {
                RepeatMode.OFF -> Icons.Filled.Repeat to R.string.player_repeat_off
                RepeatMode.ALL -> Icons.Filled.Repeat to R.string.player_repeat_all
                RepeatMode.ONE -> Icons.Filled.RepeatOne to R.string.player_repeat_one
            }
            Icon(icon, contentDescription = stringResource(description), tint = if (state.repeatMode == RepeatMode.OFF) inactive else active)
        }
    }
}

@Composable
private fun SecondaryActions(
    state: PlayerState,
    sleepRemainingProvider: () -> Long?,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val sleepLabel = when (val timer = state.sleepTimer) {
        SleepTimer.Off -> stringResource(R.string.player_sleep_short)
        SleepTimer.EndOfTrack -> stringResource(R.string.player_sleep_end_of_track_short)
        // Lu ici (et non plus haut) : seul ce bloc se recompose à chaque tick de la minuterie.
        is SleepTimer.At -> formatDuration(sleepRemainingProvider() ?: timer.remainingMs)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ToolButton(Icons.Filled.Speed, formatSpeed(state.speed), active = state.speed != 1f, onClick = onSpeed)
        ToolButton(Icons.Filled.Bedtime, sleepLabel, active = state.sleepTimer != SleepTimer.Off, onClick = onSleep)
        ToolButton(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.player_queue), active = false, onClick = onQueue)
        ToolButton(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.player_playlist), active = false, onClick = onAddToPlaylist)
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

private val PreviewTrack = Track(
    id = "abc",
    title = "Un titre de démonstration",
    artist = "Artiste",
    artistUrl = "https://www.youtube.com/channel/x",
    durationMs = 215_000,
)

private val PreviewState = PlayerState(
    isConnected = true,
    currentTrack = PreviewTrack,
    isPlaying = true,
    playWhenReady = true,
    durationMs = 215_000,
    queue = List(4) { QueueItem("u$it", PreviewTrack.copy(id = "t$it", title = "Titre $it")) },
    currentIndex = 0,
    isCurrentLiked = true,
    hasNext = true,
    repeatMode = RepeatMode.ALL,
    speed = 1.25f,
)

@Preview(showBackground = true, widthDp = 380, heightDp = 780)
@Composable
private fun FullPlayerPreview() {
    SpautifailleTheme(dynamicColor = false) {
        FullPlayerScreen(
            state = PreviewState,
            positionProvider = { PlaybackPosition(positionMs = 60_000, durationMs = 215_000) },
            actions = PlayerActions(),
            onCollapse = {},
            onOpenArtist = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 480)
@Composable
private fun FullPlayerWidePreview() {
    SpautifailleTheme(dynamicColor = false) {
        FullPlayerScreen(
            state = PreviewState.copy(sleepTimer = SleepTimer.At(0, 754_000)),
            positionProvider = { PlaybackPosition(positionMs = 60_000, durationMs = 215_000) },
            actions = PlayerActions(),
            onCollapse = {},
            onOpenArtist = {},
        )
    }
}
