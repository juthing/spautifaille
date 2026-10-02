package com.spautifaille.ui.search

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.recognition.RecognizedTrack
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.hideSheet
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

/**
 * Câblage de la reconnaissance : demande de la permission `RECORD_AUDIO`, reprise au retour des paramètres, arrêt de
 * l'écoute en arrière-plan. Renvoie la lambda à brancher sur le bouton de reconnaissance.
 */
@Composable
fun rememberRecognitionStarter(viewModel: RecognitionViewModel, state: RecognitionUiState): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.start()
        } else {
            // Après un refus, `shouldShowRequestPermissionRationale` = faux signifie « ne plus demander » : il faut passer
            // par les paramètres de l'application.
            val activity = context.findActivity()
            val canAskAgain = activity != null &&
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            viewModel.onPermissionDenied(permanentlyDenied = !canAskAgain)
        }
    }
    val request = remember(context, launcher) {
        {
            if (context.hasMicrophonePermission()) viewModel.start() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Retour des paramètres de l'application avec la permission accordée : on lance l'écoute directement.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state is RecognitionUiState.PermissionDenied && context.hasMicrophonePermission()) viewModel.start()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAppStopped() }
    return request
}

private fun Context.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/**
 * Feuille de reconnaissance (affichée tant que l'état n'est pas [RecognitionUiState.Idle]).
 *
 * @param bestResult premier titre des résultats de la recherche lancée après reconnaissance (pour « Lire »).
 * @param isSearching la recherche déclenchée est encore en cours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionSheet(
    state: RecognitionUiState,
    bestResult: Track?,
    isSearching: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: (Track) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val close: () -> Unit = { scope.hideSheet(sheetState, onDismiss) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        RecognitionContent(
            state = state,
            bestResult = bestResult,
            isSearching = isSearching,
            onRetry = onRetry,
            onClose = close,
            onOpenSettings = { context.openAppSettings() },
            onPlay = { track ->
                onPlay(track)
                close()
            },
        )
    }
}

/** Contenu sans état de la feuille (prévisualisable). */
@Composable
fun RecognitionContent(
    state: RecognitionUiState,
    bestResult: Track?,
    isSearching: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    AnimatedContent(
        targetState = state,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
        label = "recognition",
    ) { current ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding)
                .padding(top = Spacing.s, bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (current) {
                RecognitionUiState.Idle -> Spacer(Modifier.height(Spacing.s))

                is RecognitionUiState.Listening -> ListeningLayout(
                    title = stringResource(R.string.recognition_listening_title),
                    hint = stringResource(R.string.recognition_listening_hint),
                    progress = current.progress,
                    progressDescription = stringResource(
                        R.string.recognition_listening_progress,
                        (current.elapsedMs / 1000).toInt(),
                        (current.totalMs / 1000).toInt(),
                    ),
                    onCancel = {
                        haptics.click()
                        onClose()
                    },
                )

                RecognitionUiState.Identifying -> ListeningLayout(
                    title = stringResource(R.string.recognition_identifying_title),
                    hint = stringResource(R.string.recognition_identifying_hint),
                    progress = null,
                    progressDescription = stringResource(R.string.recognition_identifying_title),
                    onCancel = {
                        haptics.click()
                        onClose()
                    },
                )

                is RecognitionUiState.Found -> FoundLayout(
                    track = current.track,
                    bestResult = bestResult,
                    isSearching = isSearching,
                    onPlay = {
                        haptics.confirm()
                        bestResult?.let(onPlay)
                    },
                    onSeeResults = {
                        haptics.click()
                        onClose()
                    },
                )

                RecognitionUiState.NoMatch -> MessageLayout(
                    icon = Icons.Filled.SearchOff,
                    title = stringResource(R.string.recognition_no_match_title),
                    message = stringResource(R.string.recognition_no_match_message),
                    primaryLabel = stringResource(R.string.common_action_retry),
                    onPrimary = onRetry,
                    onClose = onClose,
                )

                is RecognitionUiState.Failed -> MessageLayout(
                    icon = Icons.Filled.ErrorOutline,
                    title = stringResource(R.string.recognition_error_title),
                    message = stringResource(current.error.toMessage()),
                    primaryLabel = stringResource(R.string.common_action_retry),
                    onPrimary = onRetry,
                    onClose = onClose,
                    isError = true,
                )

                is RecognitionUiState.PermissionDenied -> if (current.permanentlyDenied) {
                    MessageLayout(
                        icon = Icons.Filled.MicOff,
                        title = stringResource(R.string.recognition_permission_title),
                        message = stringResource(R.string.recognition_permission_settings_message),
                        primaryLabel = stringResource(R.string.recognition_open_settings),
                        onPrimary = onOpenSettings,
                        onClose = onClose,
                    )
                } else {
                    MessageLayout(
                        icon = Icons.Filled.MicOff,
                        title = stringResource(R.string.recognition_permission_title),
                        message = stringResource(R.string.recognition_permission_message),
                        primaryLabel = stringResource(R.string.recognition_permission_grant),
                        onPrimary = onRetry,
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningLayout(
    title: String,
    hint: String,
    progress: Float?,
    progressDescription: String,
    onCancel: () -> Unit,
) {
    PulseOrb(
        progress = progress,
        modifier = Modifier.semantics { contentDescription = progressDescription },
    )
    Spacer(Modifier.height(Spacing.m))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Spacing.xs))
    Text(
        hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Spacing.l))
    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.common_action_cancel)) }
}

@Composable
private fun FoundLayout(
    track: RecognizedTrack,
    bestResult: Track?,
    isSearching: Boolean,
    onPlay: () -> Unit,
    onSeeResults: () -> Unit,
) {
    Text(
        stringResource(R.string.recognition_found_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(Spacing.m))
    Artwork(
        url = track.artworkUrl,
        modifier = Modifier.size(FoundArtworkSize),
        shape = MaterialTheme.shapes.large,
        contentDescription = track.album ?: track.title,
    )
    Spacer(Modifier.height(Spacing.m))
    Text(
        track.title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        track.artist,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val details = listOfNotNull(track.album, track.releaseYear).joinToString(" · ")
    if (details.isNotEmpty()) {
        Text(
            details,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(Spacing.l))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onSeeResults) { Text(stringResource(R.string.recognition_see_results)) }
        Button(onClick = onPlay, enabled = bestResult != null) {
            if (bestResult == null && isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.recognition_play))
        }
    }
}

@Composable
private fun MessageLayout(
    icon: ImageVector,
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
    isError: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(MessageIconCircle),
        shape = CircleShape,
        color = if (isError) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(36.dp))
        }
    }
    Spacer(Modifier.height(Spacing.m))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Spacing.xs))
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Spacing.l))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.recognition_close)) }
        Button(onClick = onPrimary) { Text(primaryLabel) }
    }
}

/**
 * Disque central (icône de reconnaissance) entouré d'un anneau de progression et de trois ondes qui se propagent en
 * boucle, décalées d'un tiers de période. [progress] = nul : anneau indéterminé (identification en cours).
 * Couleurs du thème uniquement.
 */
@Composable
private fun PulseOrb(progress: Float?, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val breathing by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(PULSE_PERIOD_MS / 2), RepeatMode.Reverse),
        label = "breathing",
    )
    val waveColor = MaterialTheme.colorScheme.primary
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier.size(OrbSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(OrbSize)) {
            val center = Offset(size.width / 2, size.height / 2)
            val minRadius = CoreSize.toPx() / 2
            val maxRadius = size.minDimension / 2
            repeat(WAVES) { i ->
                val t = (phase + i.toFloat() / WAVES) % 1f
                val radius = minRadius + (maxRadius - minRadius) * t
                drawCircle(color = waveColor.copy(alpha = (1f - t) * 0.28f), radius = radius, center = center)
            }
        }
        val animatedProgress by animateFloatAsState(progress ?: 0f, tween(150, easing = LinearEasing), label = "progress")
        if (progress != null) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(CoreSize + RingGap * 2),
                strokeWidth = 4.dp,
                color = colors.primary,
                trackColor = colors.primaryContainer,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(CoreSize + RingGap * 2),
                strokeWidth = 4.dp,
                color = colors.primary,
                trackColor = colors.primaryContainer,
            )
        }
        Surface(
            modifier = Modifier
                .size(CoreSize)
                .graphicsLayer {
                    scaleX = breathing
                    scaleY = breathing
                },
            shape = CircleShape,
            color = colors.primary,
            contentColor = colors.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
    }
}

private const val PULSE_PERIOD_MS = 2400
private const val WAVES = 3
private val OrbSize: Dp = 200.dp
private val CoreSize: Dp = 88.dp
private val RingGap: Dp = 8.dp
private val FoundArtworkSize: Dp = 160.dp
private val MessageIconCircle: Dp = 80.dp

private val PreviewTrack = RecognizedTrack(
    title = "Papaoutai",
    artist = "Stromae",
    album = "Racine carrée",
    releaseYear = "2013",
)

@Preview(showBackground = true)
@Composable
private fun RecognitionListeningPreview() {
    SpautifailleTheme(dynamicColor = false) {
        RecognitionContent(
            state = RecognitionUiState.Listening(5_000, 12_000),
            bestResult = null, isSearching = false,
            onRetry = {}, onClose = {}, onOpenSettings = {}, onPlay = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecognitionFoundPreview() {
    SpautifailleTheme(dynamicColor = false) {
        RecognitionContent(
            state = RecognitionUiState.Found(PreviewTrack),
            bestResult = Track(id = "x", title = "Papaoutai", artist = "Stromae"), isSearching = false,
            onRetry = {}, onClose = {}, onOpenSettings = {}, onPlay = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecognitionNoMatchPreview() {
    SpautifailleTheme(dynamicColor = false) {
        RecognitionContent(
            state = RecognitionUiState.NoMatch,
            bestResult = null, isSearching = false,
            onRetry = {}, onClose = {}, onOpenSettings = {}, onPlay = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecognitionFailedPreview() {
    SpautifailleTheme(dynamicColor = false) {
        RecognitionContent(
            state = RecognitionUiState.Failed(AppError.Network),
            bestResult = null, isSearching = false,
            onRetry = {}, onClose = {}, onOpenSettings = {}, onPlay = {},
        )
    }
}
