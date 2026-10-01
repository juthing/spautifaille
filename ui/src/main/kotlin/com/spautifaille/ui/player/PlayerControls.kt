package com.spautifaille.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.RepeatMode
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs

private val SeekThumbGap = 6.dp
private val SeekTrackInnerCorner = 3.dp

/**
 * Barre de progression : piste épaisse avec une poignée verticale fine ; piste et poignée grossissent pendant
 * le glissement. L'état de glissement est local pour ne pas lutter contre les mises à jour de position ;
 * après le relâchement, la valeur visée est conservée brièvement jusqu'à ce que le lecteur l'ait rattrapée.
 * Haptique : un cran au début et à la fin du glissement, un cran discret tous les 10 s de position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeekBar(
    positionProvider: () -> PlaybackPosition,
    fallbackDurationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val position = positionProvider()
    val duration = if (position.durationMs > 0) position.durationMs else fallbackDurationMs
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    val lastHapticBucket = remember { mutableLongStateOf(-1L) }

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
    val remainingMs = (duration - shownMs).coerceAtLeast(0)
    val dragging = dragFraction != null

    val sizeSpec = spring<Dp>(stiffness = Spring.StiffnessMedium)
    val trackHeight by animateDpAsState(if (dragging) 14.dp else 8.dp, sizeSpec, label = "seekTrack")
    val thumbWidth by animateDpAsState(if (dragging) 3.dp else 5.dp, sizeSpec, label = "seekThumbW")
    val thumbHeight by animateDpAsState(if (dragging) 44.dp else 32.dp, sizeSpec, label = "seekThumbH")
    val colors = MaterialTheme.colorScheme
    val activeColor = if (duration > 0) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    val seekLabel = stringResource(R.string.player_seek_label)
    val seekState = stringResource(R.string.player_seek_state, formatDuration(shownMs), formatDuration(duration))

    Column(modifier = modifier) {
        Slider(
            value = fraction,
            onValueChange = { value ->
                val bucket = seekHapticBucket((value * duration).toLong())
                if (dragFraction == null) {
                    haptics.tick()
                    lastHapticBucket.longValue = bucket
                } else if (bucket != lastHapticBucket.longValue) {
                    haptics.frequentTick()
                    lastHapticBucket.longValue = bucket
                }
                dragFraction = value
            },
            onValueChangeFinished = {
                val target = dragFraction
                if (target != null && duration > 0) {
                    val ms = (target * duration).toLong()
                    pendingSeekMs = ms
                    onSeek(ms)
                    haptics.tick()
                }
                dragFraction = null
            },
            enabled = duration > 0,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = seekLabel
                    stateDescription = seekState
                },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(width = thumbWidth, height = thumbHeight)
                        .background(activeColor, CircleShape),
                )
            },
            track = { sliderState ->
                SeekTrack(
                    sliderState = sliderState,
                    thumbWidth = thumbWidth,
                    height = trackHeight,
                    activeColor = activeColor,
                    inactiveColor = colors.onSurface.copy(alpha = 0.16f),
                )
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val timeColor = if (dragging) colors.onSurface else colors.onSurfaceVariant
            Text(
                text = formatDuration(shownMs),
                style = MaterialTheme.typography.labelMedium,
                color = timeColor,
            )
            Text(
                text = "-" + formatDuration(remainingMs),
                style = MaterialTheme.typography.labelMedium,
                color = timeColor,
            )
        }
    }
}

/**
 * Piste dessinée à la main : partie jouée / restante séparées par un espace autour de la poignée, extrémités
 * arrondies et coins intérieurs plus serrés. Dans le `Slider` M3, la piste est mesurée sans la largeur de la
 * poignée : la position de la poignée est donc simplement `fraction * largeur`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekTrack(
    sliderState: SliderState,
    thumbWidth: Dp,
    height: Dp,
    activeColor: Color,
    inactiveColor: Color,
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val range = sliderState.valueRange
        val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
        val fraction = ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
        val h = size.height
        val outer = CornerRadius(h / 2f)
        val inner = CornerRadius(SeekTrackInnerCorner.toPx())
        val halfGap = SeekThumbGap.toPx() + thumbWidth.toPx() / 2f
        val centerX = fraction * size.width

        val activeEnd = centerX - halfGap
        if (activeEnd > 0f) {
            drawPath(
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 0f, top = 0f, right = activeEnd, bottom = h,
                            topLeftCornerRadius = outer, bottomLeftCornerRadius = outer,
                            topRightCornerRadius = inner, bottomRightCornerRadius = inner,
                        ),
                    )
                },
                color = activeColor,
            )
        }
        val inactiveStart = centerX + halfGap
        if (inactiveStart < size.width) {
            drawPath(
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = inactiveStart, top = 0f, right = size.width, bottom = h,
                            topLeftCornerRadius = inner, bottomLeftCornerRadius = inner,
                            topRightCornerRadius = outer, bottomRightCornerRadius = outer,
                        ),
                    )
                },
                color = inactiveColor,
            )
        }
    }
}

private val PlayButtonSize = 80.dp
private val PlayingCorner = 26.dp
private val PausedCorner = 40.dp

/**
 * Commandes hiérarchisées : grand bouton lecture / pause au centre (sa forme passe de rond à carré arrondi
 * selon l'état), précédent / suivant autour, aléatoire et répétition sur les côtés avec un état actif très
 * visible (couleur d'accent + point indicateur).
 */
@Composable
internal fun PlayerControls(state: PlayerState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val haptics = LocalAppHaptics.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ToggleIconButton(
                checked = state.shuffleEnabled,
                icon = Icons.Filled.Shuffle,
                contentDescription = stringResource(
                    if (state.shuffleEnabled) R.string.player_shuffle_on else R.string.player_shuffle_off,
                ),
                onClick = {
                    haptics.toggle(!state.shuffleEnabled)
                    actions.onToggleShuffle()
                },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = {
                    haptics.click()
                    actions.onPrevious()
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.common_action_previous),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        PlayPauseButton(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering && state.playWhenReady,
            onClick = {
                haptics.click()
                actions.onPlayPause()
            },
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = {
                    haptics.click()
                    actions.onNext()
                },
                enabled = state.hasNext,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.common_action_next),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val (icon, description) = when (state.repeatMode) {
                RepeatMode.OFF -> Icons.Filled.Repeat to R.string.player_repeat_off
                RepeatMode.ALL -> Icons.Filled.Repeat to R.string.player_repeat_all
                RepeatMode.ONE -> Icons.Filled.RepeatOne to R.string.player_repeat_one
            }
            ToggleIconButton(
                checked = state.repeatMode != RepeatMode.OFF,
                icon = icon,
                contentDescription = stringResource(description),
                onClick = {
                    // Le cycle est OFF -> ALL -> ONE -> OFF : « activé » après l'appui sauf depuis ONE.
                    haptics.toggle(state.repeatMode != RepeatMode.ONE)
                    actions.onCycleRepeat()
                },
            )
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit) {
    val corner by animateDpAsState(
        targetValue = if (isPlaying) PlayingCorner else PausedCorner,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "playCorner",
    )
    Box(contentAlignment = Alignment.Center) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(PlayButtonSize),
            shape = RoundedCornerShape(corner),
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn(tween(160, delayMillis = 60)) + scaleIn(tween(160, delayMillis = 60), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.6f))
                },
                label = "playPauseIcon",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.common_action_pause else R.string.common_action_play),
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        if (isBuffering) {
            CircularProgressIndicator(modifier = Modifier.size(PlayButtonSize + 12.dp), strokeWidth = 3.dp)
        }
    }
}

/** Bouton à bascule (aléatoire, répétition) : teinte d'accent + point indicateur quand il est actif. */
@Composable
private fun ToggleIconButton(
    checked: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val dotScale by animateFloatAsState(if (checked) 1f else 0f, tween(200), label = "toggleDot")
    val dotColor = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.BottomCenter) {
        IconToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            colors = IconButtonDefaults.iconToggleButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
        Box(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(5.dp)
                .graphicsLayer {
                    alpha = dotScale
                    scaleX = dotScale
                    scaleY = dotScale
                }
                .background(dotColor, CircleShape),
        )
    }
}

/**
 * Barre d'actions du bas : file d'attente, minuterie de sommeil (temps restant si active), vitesse (valeur si
 * différente de 1×) et j'aime (cœur animé).
 */
@Composable
internal fun PlayerActionBar(
    state: PlayerState,
    trackId: String,
    sleepRemainingProvider: () -> Long?,
    onQueue: () -> Unit,
    onSleep: () -> Unit,
    onSpeed: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PlayerActionButton(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = stringResource(R.string.player_queue),
                active = false,
                onClick = {
                    haptics.click()
                    onQueue()
                },
                modifier = Modifier.weight(1f),
            )
            SleepTimerAction(
                timer = state.sleepTimer,
                remainingProvider = sleepRemainingProvider,
                onClick = {
                    haptics.click()
                    onSleep()
                },
                modifier = Modifier.weight(1f),
            )
            PlayerActionButton(
                icon = Icons.Filled.Speed,
                label = if (state.speed != 1f) formatSpeed(state.speed) else stringResource(R.string.player_speed_short),
                active = state.speed != 1f,
                onClick = {
                    haptics.click()
                    onSpeed()
                },
                modifier = Modifier.weight(1f),
            )
            // Nouvelle instance par titre : pas d'animation de rebond à l'ouverture ni au changement de titre.
            key(trackId) {
                LikeAction(
                    isLiked = state.isCurrentLiked,
                    onToggle = {
                        haptics.toggle(!state.isCurrentLiked)
                        onToggleLike()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Isolé pour que seul ce bouton se recompose à chaque seconde de la minuterie. */
@Composable
private fun SleepTimerAction(
    timer: SleepTimer,
    remainingProvider: () -> Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (timer) {
        SleepTimer.Off -> stringResource(R.string.player_sleep_short)
        SleepTimer.EndOfTrack -> stringResource(R.string.player_sleep_end_of_track_short)
        is SleepTimer.At -> formatDuration(remainingProvider() ?: timer.remainingMs)
    }
    PlayerActionButton(
        icon = Icons.Filled.Bedtime,
        label = label,
        active = timer != SleepTimer.Off,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun LikeAction(isLiked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    var firstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(isLiked) {
        if (firstComposition) {
            firstComposition = false
            return@LaunchedEffect
        }
        scale.snapTo(if (isLiked) 0.5f else 0.8f)
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
    }
    PlayerActionButton(
        icon = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        label = stringResource(if (isLiked) R.string.player_liked_label else R.string.player_like_label),
        contentDescription = stringResource(if (isLiked) R.string.common_action_unlike else R.string.common_action_like),
        active = isLiked,
        onClick = onToggle,
        modifier = modifier,
        iconModifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}

/**
 * Action de la barre du bas : icône dans une pastille (teintée si [active], comme l'indicateur d'une barre de
 * navigation) et libellé.
 */
@Composable
private fun PlayerActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    iconModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val pill by animateColorAsState(if (active) colors.primaryContainer else Color.Transparent, tween(200), label = "actionPill")
    val tint by animateColorAsState(if (active) colors.onPrimaryContainer else colors.onSurfaceVariant, tween(200), label = "actionTint")
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 32.dp)
                .background(pill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = iconModifier.size(24.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
        )
    }
}
