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
import com.spautifaille.ui.artist.ArtistRoute as ArtistScreenRoute
import com.spautifaille.ui.discovery.DiscoveryRoute
import com.spautifaille.ui.discovery.DiscoveryScreenRoot
import com.spautifaille.ui.downloads.DownloadsScreenRoot
import com.spautifaille.ui.home.HomeScreenRoot
import com.spautifaille.ui.library.LibraryRoute as LibraryScreenRoute
import com.spautifaille.ui.playlist.PlaylistDetailRoute
import com.spautifaille.ui.remoteplaylist.RemotePlaylistRoute as RemotePlaylistScreenRoute
import com.spautifaille.ui.settings.SettingsRoute as SettingsScreenRoute
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
                onOpenDiscovery = { navController.navigate(DiscoveryRoute) },
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

/** Destinations secondaires (bibliothèque, playlists, artiste, paramètres…). */
private fun androidx.navigation.NavGraphBuilder.integrationDestinations(navController: NavHostController) {
    val openPlaylist: (Long) -> Unit = { id -> navController.navigate(PlaylistRoute(id)) }
    val openArtist: (String) -> Unit = { url -> navController.navigate(ArtistRoute(url)) }
    val openRemotePlaylist: (String) -> Unit = { url -> navController.navigate(RemotePlaylistRoute(url)) }
    val back: () -> Unit = { navController.navigateUp() }

    composable<LibraryRoute> {
        LibraryScreenRoute(
            onOpenPlaylist = openPlaylist,
            onOpenDownloads = { navController.navigate(DownloadsRoute) },
            onOpenImport = { navController.navigate(ImportRoute) },
            onOpenArtist = openArtist,
            onOpenSettings = { navController.navigate(SettingsRoute) },
        )
    }
    // Les ViewModels lisent les arguments de route (`id`, `url`) depuis leur SavedStateHandle.
    composable<PlaylistRoute> { PlaylistDetailRoute(onBack = back, onOpenArtist = openArtist) }
    composable<RemotePlaylistRoute> { RemotePlaylistScreenRoute(onBack = back, onOpenPlaylist = openPlaylist) }
    composable<ArtistRoute> { ArtistScreenRoute(onBack = back, onOpenRemotePlaylist = openRemotePlaylist) }
    composable<SettingsRoute> {
        SettingsScreenRoute(onBack = back, onOpenDownloads = { navController.navigate(DownloadsRoute) })
    }
    composable<DiscoveryRoute> { DiscoveryScreenRoot(onBack = back, onOpenArtist = openArtist) }
    composable<DownloadsRoute> { DownloadsScreenRoot(onBack = back) }
    // TODO(integration J4b) : écran d'import
    composable<ImportRoute> { PlaceholderScreen(title = stringResource(R.string.placeholder_import)) }
}
