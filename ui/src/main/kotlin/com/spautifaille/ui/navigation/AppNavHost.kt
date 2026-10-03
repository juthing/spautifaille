package com.spautifaille.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.spautifaille.domain.model.Playlist
import com.spautifaille.ui.artist.ArtistRoute as ArtistScreenRoute
import com.spautifaille.ui.discovery.DiscoveryRoute
import com.spautifaille.ui.discovery.DiscoveryScreenRoot
import com.spautifaille.ui.downloads.DownloadsScreenRoot
import com.spautifaille.ui.followed.FollowedArtistsScreenRoot
import com.spautifaille.ui.history.HistoryScreenRoot
import com.spautifaille.ui.home.HomeScreenRoot
import com.spautifaille.ui.importer.ImportReviewRoute
import com.spautifaille.ui.importer.ImportReviewScreenRoot
import com.spautifaille.ui.importer.ImportScreenRoot
import com.spautifaille.ui.library.LibraryRoute as LibraryScreenRoute
import com.spautifaille.ui.playlist.PlaylistDetailRoute
import com.spautifaille.ui.remoteplaylist.RemotePlaylistRoute as RemotePlaylistScreenRoute
import com.spautifaille.ui.settings.SettingsCategoryRoute as SettingsCategoryScreenRoute
import com.spautifaille.ui.youtube.AccountAvatarAction
import com.spautifaille.ui.youtube.YouTubeAccountScreenRoot
import com.spautifaille.ui.youtube.YouTubeLoginScreenRoot
import com.spautifaille.ui.youtube.YouTubePlaylistPickerScreenRoot
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
    val openYouTubeLogin: () -> Unit = { navController.navigate(YouTubeLoginRoute) { launchSingleTop = true } }
    val openYouTubeAccount: () -> Unit = { navController.navigate(YouTubeAccountRoute) { launchSingleTop = true } }
    // Avatar du compte YouTube, commun aux barres d'application d'Accueil, de Recherche et de Bibliothèque.
    val accountAction: @Composable () -> Unit = {
        AccountAvatarAction(onSignIn = openYouTubeLogin, onOpenAccount = openYouTubeAccount)
    }
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
                onOpenLiked = { navController.navigate(PlaylistRoute(Playlist.LIKED_ID)) },
                onOpenPlaylist = { id -> navController.navigate(PlaylistRoute(id)) },
                onOpenDiscovery = { navController.navigate(DiscoveryRoute) },
                onOpenArtist = { url -> navController.navigate(ArtistRoute(url)) },
                onOpenFollowedArtists = { navController.navigate(FollowedArtistsRoute) },
                onOpenHistory = { navController.navigate(HistoryRoute) },
                onOpenSearch = { navController.navigate(SearchRoute) { launchSingleTop = true } },
                topBarActions = { accountAction() },
            )
        }
        composable<SearchRoute> {
            SearchScreenRoot(
                onBack = { navController.navigateUp() },
                onOpenPlaylist = { url -> navController.navigate(RemotePlaylistRoute(url)) },
                onOpenArtist = { url -> navController.navigate(ArtistRoute(url)) },
                accountAction = accountAction,
            )
        }
        integrationDestinations(navController, accountAction, openYouTubeLogin, openYouTubeAccount)
    }
}

/** Destinations secondaires (bibliothèque, playlists, artiste, réglages…). */
private fun androidx.navigation.NavGraphBuilder.integrationDestinations(
    navController: NavHostController,
    accountAction: @Composable () -> Unit,
    openYouTubeLogin: () -> Unit,
    openYouTubeAccount: () -> Unit,
) {
    val openPlaylist: (Long) -> Unit = { id -> navController.navigate(PlaylistRoute(id)) }
    val openArtist: (String) -> Unit = { url -> navController.navigate(ArtistRoute(url)) }
    val openRemotePlaylist: (String) -> Unit = { url -> navController.navigate(RemotePlaylistRoute(url)) }
    val back: () -> Unit = { navController.navigateUp() }

    composable<LibraryRoute> {
        LibraryScreenRoute(
            onOpenPlaylist = openPlaylist,
            onOpenImport = { navController.navigate(ImportRoute) },
            onOpenYouTubeImport = { navController.navigate(YouTubePlaylistPickerRoute) },
            topBarActions = { accountAction() },
        )
    }
    // Les ViewModels lisent les arguments de route (`id`, `url`) depuis leur SavedStateHandle.
    composable<PlaylistRoute> { PlaylistDetailRoute(onBack = back, onOpenArtist = openArtist) }
    composable<RemotePlaylistRoute> { RemotePlaylistScreenRoute(onBack = back, onOpenPlaylist = openPlaylist) }
    composable<ArtistRoute> { ArtistScreenRoute(onBack = back, onOpenRemotePlaylist = openRemotePlaylist) }
    composable<SettingsRoute> {
        // Onglet racine : pas de retour.
        SettingsScreenRoute(
            onOpenCategory = { category -> navController.navigate(SettingsCategoryRoute(category.key)) },
            onOpenImport = { navController.navigate(ImportRoute) },
            onOpenYouTubeAccount = openYouTubeAccount,
        )
    }
    composable<YouTubeAccountRoute> {
        YouTubeAccountScreenRoot(onBack = back, onSignIn = openYouTubeLogin)
    }
    composable<YouTubeLoginRoute> {
        YouTubeLoginScreenRoot(onBack = back, onSignedIn = back)
    }
    composable<YouTubePlaylistPickerRoute> {
        YouTubePlaylistPickerScreenRoot(
            onBack = back,
            onImported = { ids ->
                // Une seule playlist importée : on l'ouvre ; sinon retour à la Bibliothèque qui les affiche.
                if (ids.size == 1) {
                    navController.popBackStack()
                    openPlaylist(ids.first())
                } else {
                    navController.popBackStack()
                }
            },
        )
    }
    composable<DiscoveryRoute> { DiscoveryScreenRoot(onBack = back, onOpenArtist = openArtist) }
    composable<DownloadsRoute> { DownloadsScreenRoot(onBack = back) }
    composable<ImportRoute> {
        ImportScreenRoot(
            onBack = back,
            onOpenPlaylist = openPlaylist,
            onOpenReview = { jobId -> navController.navigate(ImportReviewRoute(jobId)) },
        )
    }
    composable<ImportReviewRoute> { ImportReviewScreenRoot(onBack = back) }
    composable<HistoryRoute> { HistoryScreenRoot(onBack = back, onOpenArtist = openArtist) }
    composable<FollowedArtistsRoute> { FollowedArtistsScreenRoot(onBack = back, onOpenArtist = openArtist) }
    composable<SettingsCategoryRoute> { entry ->
        SettingsCategoryScreenRoute(
            categoryKey = entry.toRoute<SettingsCategoryRoute>().category,
            onBack = back,
            onOpenDownloads = { navController.navigate(DownloadsRoute) },
        )
    }
}
