package com.spautifaille.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.spautifaille.domain.model.Playlist
import com.spautifaille.ui.R
import com.spautifaille.ui.home.HomeScreenRoot
import com.spautifaille.ui.search.SearchScreenRoot

private const val EnterMillis = 220
private const val ExitMillis = 90
private const val InitialScale = 0.96f

/** Graphe de navigation de l'application. */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        // Fondu enchaîné + léger zoom (« fade through » de Material).
        enterTransition = { fadeIn(tween(EnterMillis, delayMillis = ExitMillis)) + scaleIn(tween(EnterMillis, delayMillis = ExitMillis), InitialScale) },
        exitTransition = { fadeOut(tween(ExitMillis)) },
        popEnterTransition = { fadeIn(tween(EnterMillis, delayMillis = ExitMillis)) + scaleIn(tween(EnterMillis, delayMillis = ExitMillis), InitialScale) },
        popExitTransition = { fadeOut(tween(ExitMillis)) + scaleOut(tween(ExitMillis), InitialScale) },
    ) {
        composable<HomeRoute> {
            HomeScreenRoot(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenLiked = { navController.navigate(PlaylistRoute(Playlist.LIKED_ID)) },
            )
        }
        composable<SearchRoute> {
            SearchScreenRoot(
                onOpenPlaylist = { url -> navController.navigate(RemotePlaylistRoute(url)) },
                onOpenArtist = { url -> navController.navigate(ArtistRoute(url)) },
            )
        }
        integrationDestinations(navController)
    }
}

/**
 * TODO(integration) : destinations dont les écrans sont écrits dans d'autres packages
 * (`library`, `playlist`, `remoteplaylist`, `artist`, `settings`, `downloads`, `import`).
 * L'intégrateur remplace chaque [PlaceholderScreen] par le vrai écran (`XxxScreenRoot(...)`) et câble
 * la navigation avec [navController] (ex. `navController.navigate(PlaylistRoute(id))`, `navigateUp()`).
 */
private fun androidx.navigation.NavGraphBuilder.integrationDestinations(navController: NavHostController) {
    // TODO(integration): LibraryScreen (onOpenPlaylist -> PlaylistRoute, onOpenDownloads -> DownloadsRoute, onOpenImport -> ImportRoute)
    composable<LibraryRoute> { PlaceholderScreen(title = stringResource(R.string.nav_library)) }
    // TODO(integration): PlaylistScreen(id) — `backStackEntry.toRoute<PlaylistRoute>()`
    composable<PlaylistRoute> { PlaceholderScreen(title = stringResource(R.string.placeholder_playlist)) }
    // TODO(integration): RemotePlaylistScreen(url) — `backStackEntry.toRoute<RemotePlaylistRoute>()`
    composable<RemotePlaylistRoute> { PlaceholderScreen(title = stringResource(R.string.placeholder_remote_playlist)) }
    // TODO(integration): ArtistScreen(url) — `backStackEntry.toRoute<ArtistRoute>()`
    composable<ArtistRoute> { PlaceholderScreen(title = stringResource(R.string.placeholder_artist)) }
    // TODO(integration): SettingsScreen (onBack = navController::navigateUp, onOpenDownloads, onOpenImport)
    composable<SettingsRoute> { PlaceholderScreen(title = stringResource(R.string.nav_settings)) }
    // TODO(integration): DownloadsScreen
    composable<DownloadsRoute> { PlaceholderScreen(title = stringResource(R.string.placeholder_downloads)) }
    // TODO(integration): ImportScreen
    composable<ImportRoute> { PlaceholderScreen(title = stringResource(R.string.placeholder_import)) }
}
