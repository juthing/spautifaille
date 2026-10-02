package com.spautifaille.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.common.rememberAppHaptics
import com.spautifaille.ui.lyrics.LyricsRoute
import com.spautifaille.ui.player.FullPlayerScreen
import com.spautifaille.ui.player.MiniPlayer
import com.spautifaille.ui.player.MiniPlayerHeight
import com.spautifaille.ui.player.PlayerActions
import com.spautifaille.ui.player.PlayerTransition
import com.spautifaille.ui.player.PlayerViewModel
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlinx.coroutines.flow.collectLatest

/**
 * Point d'entrée de l'UI : à appeler depuis `setContent { SpautifailleAppUi() }` dans `MainActivity`
 * (annotée `@AndroidEntryPoint`). Gère seul le thème (clair / sombre / dynamique, lus dans les réglages),
 * la navigation, le mini lecteur, le lecteur plein écran, les snackbars et la permission de notifications.
 */
@Composable
fun SpautifailleAppUi(
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val theme by appViewModel.themeSettings.collectAsStateWithLifecycle()
    val loaded = theme
    SpautifailleTheme(
        themeMode = loaded?.themeMode ?: com.spautifaille.domain.model.ThemeMode.SYSTEM,
        dynamicColor = loaded?.dynamicColor ?: true,
    ) {
        if (loaded == null) {
            // Réglages en cours de chargement : fond neutre pour éviter un flash du mauvais thème.
            Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        } else {
            CompositionLocalProvider(LocalAppHaptics provides rememberAppHaptics()) {
                AppShell(appViewModel = appViewModel, playerViewModel = playerViewModel, modifier = modifier)
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppShell(
    appViewModel: AppViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val haptics = LocalAppHaptics.current
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val positionState = playerViewModel.position.collectAsStateWithLifecycle()
    // Non lu ici : seuls les composables qui affichent la minuterie se recomposent à chaque seconde.
    val sleepRemainingState = playerViewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var backProgress by remember { mutableStateOf(0f) }

    val hasTrack = playerState.currentTrack != null
    LaunchedEffect(hasTrack) { if (!hasTrack) playerExpanded = false }

    NotificationPermissionEffect(appViewModel)
    SnackbarEffect(appViewModel, snackbarHostState)

    PredictiveBackHandler(enabled = playerExpanded) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            playerExpanded = false
        } finally {
            backProgress = 0f
        }
    }

    val playerActions = remember(playerViewModel) {
        PlayerActions(
            onPlayPause = playerViewModel::togglePlayPause,
            onNext = playerViewModel::next,
            onPrevious = playerViewModel::previous,
            onSeek = playerViewModel::seekTo,
            onToggleShuffle = playerViewModel::toggleShuffle,
            onCycleRepeat = playerViewModel::cycleRepeatMode,
            onToggleLike = playerViewModel::toggleLike,
            onSetSpeed = playerViewModel::setSpeed,
            onSleepMinutes = playerViewModel::setSleepTimerMinutes,
            onSleepEndOfTrack = playerViewModel::setSleepTimerEndOfTrack,
            onCancelSleep = playerViewModel::cancelSleepTimer,
            onSkipToQueueItem = playerViewModel::skipToQueueItem,
            onMoveQueueItem = playerViewModel::moveQueueItem,
            onRemoveQueueItem = playerViewModel::removeQueueItem,
            onClearQueue = playerViewModel::clearQueue,
        )
    }

    // Dernier titre connu : le mini lecteur le garde affiché pendant son animation de sortie.
    var lastTrack by remember { mutableStateOf(playerState.currentTrack) }
    playerState.currentTrack?.let { lastTrack = it }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    var selectedTop by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(currentDestination) {
        TopLevelDestination.entries.forEachIndexed { index, destination ->
            if (currentDestination?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true) {
                selectedTop = index
            }
        }
    }

    val navigationSuiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())
    val isBottomBar = navigationSuiteType == NavigationSuiteType.ShortNavigationBarCompact ||
        navigationSuiteType == NavigationSuiteType.ShortNavigationBarMedium ||
        navigationSuiteType == NavigationSuiteType.NavigationBar

    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val sharedScope = this
        Box(Modifier.fillMaxSize()) {
            NavigationSuiteScaffold(
                modifier = Modifier.fillMaxSize(),
                navigationSuiteType = navigationSuiteType,
                navigationItems = {
                    TopLevelDestination.entries.forEachIndexed { index, destination ->
                        NavigationSuiteItem(
                            selected = index == selectedTop,
                            onClick = {
                                haptics.click()
                                navController.navigateToTopLevel(destination, reselected = index == selectedTop)
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                            navigationSuiteType = navigationSuiteType,
                        )
                    }
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                // Le mini lecteur (ou la barre) occupe déjà le bas : les écrans ne réservent pas
                                // une seconde fois l'espace des barres système.
                                if (hasTrack) {
                                    Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        AppNavHost(navController = navController, modifier = Modifier.fillMaxSize())
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = Spacing.s),
                        )
                    }
                    MiniPlayerDock(
                        visible = hasTrack,
                        collapsed = !playerExpanded,
                        track = lastTrack,
                        playerState = playerState,
                        positionState = positionState,
                        actions = playerActions,
                        sharedScope = sharedScope,
                        applyBottomInset = !isBottomBar,
                        onExpand = { playerExpanded = true },
                    )
                }
            }

            AnimatedVisibility(
                visible = playerExpanded && hasTrack,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                FullPlayerScreen(
                    state = playerState,
                    positionProvider = { positionState.value },
                    sleepRemainingProvider = { sleepRemainingState.value },
                    actions = playerActions,
                    onCollapse = { playerExpanded = false },
                    onOpenArtist = { url ->
                        playerExpanded = false
                        navController.navigate(ArtistRoute(url))
                    },
                    transition = PlayerTransition(sharedScope, this),
                    lyricsContent = { lyricsModifier -> LyricsRoute(lyricsModifier) },
                    modifier = Modifier.graphicsLayer {
                        val scale = 1f - 0.1f * backProgress
                        scaleX = scale
                        scaleY = scale
                    },
                )
            }

            if (playerExpanded) {
                // Le lecteur plein écran recouvre la snackbar de la navigation : on en affiche une seconde au-dessus.
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = Spacing.s),
                )
            }
        }
    }
}

/**
 * Emplacement du mini lecteur sous le contenu. Deux niveaux d'animation : l'apparition (première lecture) et
 * le passage mini -> plein écran, ce dernier portant les éléments partagés. L'emplacement garde sa hauteur
 * pendant l'ouverture du lecteur pour éviter tout saut de mise en page.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerDock(
    visible: Boolean,
    collapsed: Boolean,
    track: Track?,
    playerState: PlayerState,
    positionState: State<PlaybackPosition>,
    actions: PlayerActions,
    sharedScope: SharedTransitionScope,
    applyBottomInset: Boolean,
    onExpand: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (applyBottomInset) {
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    } else {
                        Modifier
                    },
                )
                .height(MiniPlayerHeight),
        ) {
            MiniPlayerTransition(collapsed) { visibilityScope ->
                if (track != null) {
                    MiniPlayer(
                        track = track,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering && playerState.playWhenReady,
                        hasNext = playerState.hasNext,
                        progress = { positionState.value.progressFraction(playerState.durationMs) },
                        onExpand = onExpand,
                        onPlayPause = actions.onPlayPause,
                        onNext = actions.onNext,
                        onPrevious = actions.onPrevious,
                        transition = PlayerTransition(sharedScope, visibilityScope),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerTransition(visible: Boolean, content: @Composable (AnimatedVisibilityScope) -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) { content(this) }
}

private fun PlaybackPosition.progressFraction(fallbackDurationMs: Long): Float {
    val duration = if (durationMs > 0) durationMs else fallbackDurationMs
    return if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination, reselected: Boolean) {
    if (reselected) {
        // Re-toucher l'onglet courant revient à sa racine.
        popBackStack(destination.route, inclusive = false)
        return
    }
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun NotificationPermissionEffect(appViewModel: AppViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(appViewModel) {
        appViewModel.notificationPermissionRequests.collect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun SnackbarEffect(appViewModel: AppViewModel, hostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(appViewModel, hostState) {
        appViewModel.messages.collectLatest { message ->
            hostState.showSnackbar(message.asString(context))
        }
    }
}
