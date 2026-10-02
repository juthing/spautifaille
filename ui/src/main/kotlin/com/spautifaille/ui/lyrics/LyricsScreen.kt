package com.spautifaille.ui.lyrics

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.lyrics.LyricLine
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.ShimmerBox
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlinx.coroutines.delay
import kotlin.math.max

/** Délai sans interaction avant que le défilement automatique reprenne après un défilement manuel. */
internal const val AUTO_SCROLL_RESUME_MS = 4_000L
private const val LINE_ANIMATION_MS = 350
private const val SCROLL_ANIMATION_MS = 500
private const val INACTIVE_ALPHA = 0.35f
private const val INACTIVE_SCALE = 0.92f
private const val ACTIVE_ZONE_FRACTION = 1f / 3f

/** Point d'entrée : récupère le [LyricsViewModel] et affiche [LyricsScreen]. Ne charge que tant qu'il est composé. */
@Composable
fun LyricsRoute(modifier: Modifier = Modifier, viewModel: LyricsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LyricsScreen(state = state, onSeek = viewModel::seekTo, onRetry = viewModel::retry, modifier = modifier)
}

/**
 * Paroles façon Apple Music : grandes lignes en gras, ligne courante pleinement opaque et les autres atténuées,
 * défilement automatique qui garde la ligne active dans le tiers supérieur, toucher une ligne pour s'y rendre.
 * Les couleurs viennent du thème local (celui du lecteur, teinté par la pochette).
 */
@Composable
fun LyricsScreen(
    state: LyricsUiState,
    onSeek: (positionMs: Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = state,
        modifier = modifier.fillMaxSize(),
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(LINE_ANIMATION_MS)) togetherWith fadeOut(tween(LINE_ANIMATION_MS)) },
        label = "lyricsState",
    ) { current ->
        when (current) {
            LyricsUiState.Loading -> LyricsSkeleton()
            is LyricsUiState.Synced -> SyncedLyrics(current.lines, current.activeIndex, current.source, onSeek)
            is LyricsUiState.Plain -> PlainLyrics(current.text, current.source)
            is LyricsUiState.Instrumental -> EmptyState(
                title = stringResource(R.string.lyrics_instrumental_title),
                message = stringResource(R.string.lyrics_instrumental_message),
                icon = Icons.Filled.MusicNote,
            )
            LyricsUiState.NotFound -> EmptyState(
                title = stringResource(R.string.lyrics_unavailable_title),
                message = stringResource(R.string.lyrics_unavailable_message),
                icon = Icons.Filled.Lyrics,
            )
            is LyricsUiState.Error -> if (current.error == AppError.Network) {
                ErrorState(
                    message = stringResource(R.string.lyrics_offline_message),
                    title = stringResource(R.string.lyrics_offline_title),
                    onRetry = onRetry,
                )
            } else {
                ErrorState(
                    message = current.error.toMessage(),
                    title = R.string.lyrics_unavailable_title,
                    onRetry = onRetry,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Paroles synchronisées
// -------------------------------------------------------------------------------------------------

/** Suit si un défilement vient de l'utilisateur (doigt posé ou inertie), pour ne pas le contrarier. */
private class UserScrollTracker {
    var active = false
    var positioned = false
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    activeIndex: Int,
    source: String,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val tracker = remember { UserScrollTracker() }
    var resumeAt by remember { mutableLongStateOf(0L) }
    val haptics = LocalAppHaptics.current

    val connection = remember(tracker) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) tracker.active = true
                return Offset.Zero
            }
        }
    }
    // Fin d'un défilement manuel (y compris l'inertie) : l'auto-scroll reprend après un court délai.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && tracker.active) {
                tracker.active = false
                resumeAt = SystemClock.elapsedRealtime() + AUTO_SCROLL_RESUME_MS
            }
        }
    }
    LaunchedEffect(activeIndex, resumeAt, lines) {
        if (tracker.active) return@LaunchedEffect
        val wait = resumeAt - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        if (tracker.active) return@LaunchedEffect
        val target = max(activeIndex, 0)
        if (!tracker.positioned) {
            tracker.positioned = true
            listState.scrollToItem(target)
        } else {
            listState.animateToLine(target)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(connection)
                .fadingEdges(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = maxHeight * ACTIVE_ZONE_FRACTION,
                    bottom = maxHeight * (1f - ACTIVE_ZONE_FRACTION),
                    start = Spacing.xs,
                    end = Spacing.xs,
                ),
            ) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    LyricLineItem(
                        line = line,
                        active = index == activeIndex,
                        onClick = {
                            haptics.click()
                            resumeAt = 0L
                            onSeek(line.timeMs)
                        },
                    )
                }
            }
        }
        SourceCredit(source)
    }
}

/** Amène la ligne [index] sous la zone active (haut du contenu) : glissement doux si elle est visible. */
private suspend fun LazyListState.animateToLine(index: Int) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (info == null) {
        animateScrollToItem(index)
    } else if (info.offset != 0) {
        animateScrollBy(info.offset.toFloat(), tween(SCROLL_ANIMATION_MS, easing = FastOutSlowInEasing))
    }
}

@Composable
private fun LyricLineItem(line: LyricLine, active: Boolean, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (active) 1f else INACTIVE_ALPHA, tween(LINE_ANIMATION_MS), label = "lineAlpha")
    val scale by animateFloatAsState(if (active) 1f else INACTIVE_SCALE, tween(LINE_ANIMATION_MS), label = "lineScale")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.lyrics_seek_action), onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        ) {
            if (line.isBreak) {
                BreakDots(active = active)
            } else {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Trois points pour une pause instrumentale : ils « respirent » tant que la pause est en cours. */
@Composable
private fun BreakDots(active: Boolean) {
    val description = stringResource(R.string.lyrics_instrumental_break)
    val transition = rememberInfiniteTransition(label = "breakDots")
    Row(
        modifier = Modifier
            .height(BreakHeight)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { dot ->
            val pulse = transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = DOTS_CYCLE_MS
                        0f at 0
                        1f at DOTS_PEAK_MS + dot * DOTS_STAGGER_MS using FastOutSlowInEasing
                        0f at DOTS_PEAK_MS * 2 + dot * DOTS_STAGGER_MS
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$dot",
            )
            Box(
                modifier = Modifier
                    .size(DotSize)
                    .graphicsLayer {
                        val level = if (active) pulse.value else 0f
                        val size = 0.7f + 0.3f * level
                        scaleX = size
                        scaleY = size
                        alpha = 0.5f + 0.5f * level
                    }
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape),
            )
        }
    }
}

private const val DOTS_CYCLE_MS = 1_600
private const val DOTS_PEAK_MS = 400
private const val DOTS_STAGGER_MS = 150
private val DotSize = 12.dp
private val BreakHeight = 36.dp

// -------------------------------------------------------------------------------------------------
// Paroles non synchronisées
// -------------------------------------------------------------------------------------------------

@Composable
private fun PlainLyrics(text: String, source: String) {
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadingEdges(),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.m, vertical = Spacing.l),
            )
        }
        SourceCredit(source)
    }
}

// -------------------------------------------------------------------------------------------------
// Chargement et pied de page
// -------------------------------------------------------------------------------------------------

private val SkeletonWidths = listOf(0.7f, 0.9f, 0.55f, 0.85f, 0.65f, 0.8f, 0.5f, 0.75f)

@Composable
private fun LyricsSkeleton() {
    val description = stringResource(R.string.lyrics_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.m, vertical = Spacing.l)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        SkeletonWidths.forEach { width ->
            ShimmerBox(
                modifier = Modifier.fillMaxWidth(width).height(28.dp),
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}

/** Mention discrète de la source, hors de la zone défilante. */
@Composable
private fun SourceCredit(source: String) {
    Text(
        text = stringResource(R.string.lyrics_source, source),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
            .clearAndSetSemantics { },
    )
}

/** Estompe le haut et le bas de la zone défilante (masque alpha) pour que les lignes entrent et sortent en douceur. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.08f to Color.Black,
                0.85f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

// -------------------------------------------------------------------------------------------------
// Aperçus
// -------------------------------------------------------------------------------------------------

private val PreviewLines = listOf(
    LyricLine(0, ""),
    LyricLine(14_000, "I've been tryna call"),
    LyricLine(18_000, "I've been on my own for long enough"),
    LyricLine(22_000, "Maybe you can show me how to love, maybe"),
    LyricLine(30_000, ""),
    LyricLine(40_000, "I'm going through withdrawals"),
    LyricLine(44_000, "You don't even have to do too much"),
)

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun SyncedPreview() {
    SpautifailleTheme(dynamicColor = false) {
        LyricsScreen(LyricsUiState.Synced(PreviewLines, activeIndex = 2, source = "LRCLIB"), onSeek = {}, onRetry = {})
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun BreakPreview() {
    SpautifailleTheme(dynamicColor = false) {
        LyricsScreen(LyricsUiState.Synced(PreviewLines, activeIndex = 4, source = "LRCLIB"), onSeek = {}, onRetry = {})
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun PlainPreview() {
    SpautifailleTheme(dynamicColor = false) {
        LyricsScreen(LyricsUiState.Plain("Yeah\nI've been tryna call\nI've been on my own for long enough", "LRCLIB"), {}, {})
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun LoadingPreview() {
    SpautifailleTheme(dynamicColor = false) { LyricsScreen(LyricsUiState.Loading, {}, {}) }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun NotFoundPreview() {
    SpautifailleTheme(dynamicColor = false) { LyricsScreen(LyricsUiState.NotFound, {}, {}) }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 560)
@Composable
private fun OfflinePreview() {
    SpautifailleTheme(dynamicColor = false) { LyricsScreen(LyricsUiState.Error(AppError.Network), {}, {}) }
}
