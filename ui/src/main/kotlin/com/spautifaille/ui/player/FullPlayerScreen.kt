package com.spautifaille.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.AddToPlaylistSheet
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

private enum class PlayerSheet { None, Queue, Speed, Sleep, Playlist }

private val WideLayoutMinWidth = 600.dp
private val CollapseThreshold = 140.dp
private val CollapseFlingVelocity = 1_400.dp
private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L

/**
 * Lecteur plein écran. Sans état : tout vient de [state] / [positionProvider] et remonte par [actions].
 * [positionProvider] est lu uniquement dans la barre de progression, qui se recompose seule à ~4 Hz.
 *
 * Le fond est un dégradé vertical tiré de la couleur dominante de la pochette (animé à chaque titre), et
 * l'accent de l'écran suit la même teinte ; sans pochette exploitable, ce sont les couleurs du thème.
 * Glisser la pochette change de titre ; glisser l'écran vers le bas le referme.
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
    lyricsContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val track = state.currentTrack ?: return
    val seedColor by rememberArtworkSeedColor(track.thumbnailUrl)
    FullPlayerContent(
        state = state,
        positionProvider = positionProvider,
        actions = actions,
        onCollapse = onCollapse,
        onOpenArtist = onOpenArtist,
        seedColor = seedColor,
        modifier = modifier,
        transition = transition,
        sleepRemainingProvider = sleepRemainingProvider,
        lyricsContent = lyricsContent,
    )
}

@Composable
internal fun FullPlayerContent(
    state: PlayerState,
    positionProvider: () -> PlaybackPosition,
    actions: PlayerActions,
    onCollapse: () -> Unit,
    onOpenArtist: (artistUrl: String) -> Unit,
    seedColor: Color?,
    modifier: Modifier = Modifier,
    transition: PlayerTransition? = null,
    sleepRemainingProvider: () -> Long? = { null },
    lyricsContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val track = state.currentTrack ?: return
    var sheet by rememberSaveable { mutableStateOf(PlayerSheet.None) }
    // Paroles affichées à la place de la pochette ; revient à la pochette à chaque ouverture du lecteur.
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val lyricsEnabled = lyricsContent != null
    val onToggleLyrics: (() -> Unit)? = if (lyricsEnabled) ({ showLyrics = !showLyrics }) else null

    PlayerColorScheme(seed = seedColor) { backgroundTop ->
        val haptics = LocalAppHaptics.current
        val density = LocalDensity.current
        val surface = MaterialTheme.colorScheme.surface
        val shape = MaterialTheme.shapes.extraLarge
        var dragY by remember { mutableFloatStateOf(0f) }
        val pulled by remember { derivedStateOf { dragY > 0f } }
        val collapseThresholdPx = with(density) { CollapseThreshold.toPx() }
        val collapseFlingPx = with(density) { CollapseFlingVelocity.toPx() }

        Surface(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dragY }
                .playerSharedBounds(PlayerSharedKeys.Container, transition, shape)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> dragY = (dragY + delta).coerceAtLeast(0f) },
                    onDragStopped = { velocity ->
                        if (shouldCollapsePlayer(dragY, velocity, collapseThresholdPx, collapseFlingPx)) {
                            haptics.click()
                            onCollapse()
                        } else {
                            animate(dragY, 0f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { v, _ -> dragY = v }
                        }
                    },
                ),
            color = surface,
            // Coins arrondis uniquement pendant que l'écran est tiré vers le bas.
            shape = if (pulled) RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp) else RectangleShape,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to backgroundTop(),
                                    0.55f to lerp(backgroundTop(), surface, 0.6f),
                                    1f to surface,
                                ),
                            ),
                        )
                    }
                    .safeDrawingPadding(),
            ) {
                val wide = maxWidth >= WideLayoutMinWidth && maxWidth > maxHeight
                val openArtist = track.artistUrl?.let { url -> { onOpenArtist(url) } }
                val artwork: @Composable (Modifier) -> Unit = { artworkModifier ->
                    SwipeableArtwork(
                        trackId = track.id,
                        thumbnailUrl = track.thumbnailUrl,
                        title = track.title,
                        isPlaying = state.isPlaying,
                        canSkipNext = state.hasNext,
                        onSwipePrevious = {
                            // « Précédent » ne change de titre que depuis le début du titre en cours.
                            val changes = state.hasPrevious && positionProvider().positionMs <= PREVIOUS_RESTART_THRESHOLD_MS
                            actions.onPrevious()
                            changes
                        },
                        onSwipeNext = {
                            actions.onNext()
                            true
                        },
                        transition = transition,
                        modifier = artworkModifier,
                    )
                }
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.l)) {
                    PlayerTopBar(
                        onCollapse = onCollapse,
                        onAddToPlaylist = { sheet = PlayerSheet.Playlist },
                        onOpenArtist = openArtist,
                    )
                    if (wide) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LyricsOrArtwork(
                                showLyrics = showLyrics && lyricsEnabled,
                                lyricsContent = lyricsContent,
                                artwork = artwork,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                artworkModifier = Modifier.fillMaxSize().padding(bottom = Spacing.m),
                                lyricsModifier = Modifier.fillMaxSize(),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Spacing.m),
                            ) {
                                PlayerBody(
                                    state, track, positionProvider, sleepRemainingProvider, actions, openArtist, transition,
                                    showTrackInfo = true,
                                    lyricsActive = showLyrics,
                                    onToggleLyrics = onToggleLyrics,
                                ) { sheet = it }
                            }
                        }
                    } else {
                        val lyricsVisible = showLyrics && lyricsEnabled
                        LyricsOrArtwork(
                            showLyrics = lyricsVisible,
                            lyricsContent = lyricsContent,
                            artwork = artwork,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            artworkModifier = Modifier.fillMaxSize().padding(vertical = Spacing.s),
                            lyricsModifier = Modifier.fillMaxSize(),
                            lyricsHeader = { LyricsHeader(track, onClose = { showLyrics = false }) },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                            PlayerBody(
                                state, track, positionProvider, sleepRemainingProvider, actions, openArtist, transition,
                                showTrackInfo = !lyricsVisible,
                                lyricsActive = showLyrics,
                                onToggleLyrics = onToggleLyrics,
                            ) { sheet = it }
                        }
                    }
                    Spacer(Modifier.height(Spacing.m))
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
}

@Composable
private fun PlayerBody(
    state: PlayerState,
    track: Track,
    positionProvider: () -> PlaybackPosition,
    sleepRemainingProvider: () -> Long?,
    actions: PlayerActions,
    onOpenArtist: (() -> Unit)?,
    transition: PlayerTransition?,
    showTrackInfo: Boolean,
    lyricsActive: Boolean,
    onToggleLyrics: (() -> Unit)?,
    openSheet: (PlayerSheet) -> Unit,
) {
    // Les paroles affichent déjà titre et artiste dans leur en-tête : on libère la place pour les lignes.
    AnimatedVisibility(visible = showTrackInfo) {
        TrackInfo(track = track, onArtistClick = onOpenArtist, transition = transition)
    }
    SeekBar(
        positionProvider = positionProvider,
        fallbackDurationMs = state.durationMs,
        onSeek = actions.onSeek,
        // Un glissement qui démarre sur la barre ne doit jamais refermer le lecteur.
        modifier = Modifier.blockParentDrag(),
    )
    PlayerControls(state = state, actions = actions, modifier = Modifier.blockParentDrag())
    PlayerActionBar(
        state = state,
        trackId = track.id,
        sleepRemainingProvider = sleepRemainingProvider,
        onQueue = { openSheet(PlayerSheet.Queue) },
        onSleep = { openSheet(PlayerSheet.Sleep) },
        onSpeed = { openSheet(PlayerSheet.Speed) },
        onToggleLike = actions.onToggleLike,
        lyricsActive = lyricsActive,
        onLyrics = onToggleLyrics,
    )
}

/**
 * Zone centrale : pochette ou paroles, avec fondu enchaîné. En portrait, les paroles sont précédées d'un
 * en-tête compact (vignette, titre, artiste) qui remplace le titre sous la pochette.
 */
@Composable
private fun LyricsOrArtwork(
    showLyrics: Boolean,
    lyricsContent: (@Composable (Modifier) -> Unit)?,
    artwork: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    lyricsModifier: Modifier = Modifier,
    lyricsHeader: (@Composable () -> Unit)? = null,
) {
    AnimatedContent(
        targetState = showLyrics,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(LYRICS_TRANSITION_MS)) togetherWith fadeOut(tween(LYRICS_TRANSITION_MS)) },
        label = "lyricsOrArtwork",
    ) { lyricsVisible ->
        if (lyricsVisible && lyricsContent != null) {
            Column(Modifier.fillMaxSize()) {
                lyricsHeader?.invoke()
                lyricsContent(lyricsModifier.weight(1f))
            }
        } else {
            artwork(artworkModifier)
        }
    }
}

/** En-tête compact au-dessus des paroles : pochette réduite, titre, artiste ; toucher revient à la pochette. */
@Composable
private fun LyricsHeader(track: Track, onClose: () -> Unit) {
    val haptics = LocalAppHaptics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.lyrics_close_cd)) {
                haptics.click()
                onClose()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Artwork(
            url = track.thumbnailUrl,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(LyricsHeaderArtworkSize),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            CrossfadeText(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                marquee = true,
            )
            CrossfadeText(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                marquee = true,
            )
        }
    }
}

private val LyricsHeaderArtworkSize = 56.dp
private const val LYRICS_TRANSITION_MS = 300

/** Chevron pour réduire, « En cours de lecture » au centre, menu (ajout à une playlist, page de l'artiste). */
@Composable
private fun PlayerTopBar(
    onCollapse: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: (() -> Unit)?,
) {
    val haptics = LocalAppHaptics.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            haptics.click()
            onCollapse()
        }) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_collapse))
        }
        Text(
            text = stringResource(R.string.player_now_playing),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Box {
            IconButton(onClick = {
                haptics.click()
                menuOpen = true
            }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_action_add_to_playlist)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        haptics.click()
                        onAddToPlaylist()
                    },
                )
                if (onOpenArtist != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_action_go_to_artist)) },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            haptics.click()
                            onOpenArtist()
                        },
                    )
                }
            }
        }
    }
}

/** Titre en gras qui défile s'il est trop long, artiste cliquable (page artiste) en dessous. */
@Composable
private fun TrackInfo(track: Track, onArtistClick: (() -> Unit)?, transition: PlayerTransition?) {
    val haptics = LocalAppHaptics.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        CrossfadeText(
            text = track.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            marquee = true,
            modifier = Modifier
                .fillMaxWidth()
                .playerSharedBounds(PlayerSharedKeys.Title, transition),
        )
        CrossfadeText(
            text = track.artist,
            style = MaterialTheme.typography.titleMedium,
            color = if (onArtistClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            marquee = true,
            modifier = if (onArtistClick != null) {
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable {
                        haptics.click()
                        onArtistClick()
                    }
            } else {
                Modifier
            },
        )
    }
}

private val PreviewTrack = Track(
    id = "abc",
    title = "Un titre de démonstration vraiment très long qui défile",
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
    shuffleEnabled = true,
    repeatMode = RepeatMode.ALL,
    speed = 1.25f,
)

private val PreviewSeed = Color(0xFF8E3FD1)

@Preview(showBackground = true, widthDp = 380, heightDp = 780)
@Composable
private fun FullPlayerPreview() {
    SpautifailleTheme(dynamicColor = false) {
        FullPlayerContent(
            state = PreviewState,
            positionProvider = { PlaybackPosition(positionMs = 60_000, durationMs = 215_000) },
            actions = PlayerActions(),
            onCollapse = {},
            onOpenArtist = {},
            seedColor = PreviewSeed,
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 780)
@Composable
private fun FullPlayerPausedNoSeedPreview() {
    SpautifailleTheme(dynamicColor = false) {
        FullPlayerContent(
            state = PreviewState.copy(isPlaying = false, isCurrentLiked = false, speed = 1f, repeatMode = RepeatMode.OFF),
            positionProvider = { PlaybackPosition(positionMs = 120_000, durationMs = 215_000) },
            actions = PlayerActions(),
            onCollapse = {},
            onOpenArtist = {},
            seedColor = null,
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 480)
@Composable
private fun FullPlayerWidePreview() {
    SpautifailleTheme(dynamicColor = false) {
        FullPlayerContent(
            state = PreviewState.copy(sleepTimer = SleepTimer.At(0, 754_000)),
            positionProvider = { PlaybackPosition(positionMs = 60_000, durationMs = 215_000) },
            actions = PlayerActions(),
            onCollapse = {},
            onOpenArtist = {},
            seedColor = Color(0xFF1E88E5),
        )
    }
}
