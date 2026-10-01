package com.spautifaille.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.spautifaille.ui.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/* Routes typées (Navigation Compose 2.8+). Les arguments sont sérialisés par kotlinx.serialization. */

@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

@Serializable
data object LibraryRoute

/** Playlist locale ; [id] = `Playlist.LIKED_ID` pour « Titres likés ». */
@Serializable
data class PlaylistRoute(val id: Long)

/** Playlist / album distant, identifié par son URL YouTube. */
@Serializable
data class RemotePlaylistRoute(val url: String)

/** Artiste / chaîne, identifié par l'URL de la chaîne. */
@Serializable
data class ArtistRoute(val url: String)

@Serializable
data object SettingsRoute

@Serializable
data object DownloadsRoute

@Serializable
data object ImportRoute

/** Destinations de premier niveau affichées dans la barre / le rail de navigation. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Home(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Filled.Home),
    Search(SearchRoute, SearchRoute::class, R.string.nav_search, Icons.Filled.Search),
    Library(LibraryRoute, LibraryRoute::class, R.string.nav_library, Icons.Filled.LibraryMusic),
    Settings(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Filled.Settings),
}

/** Historique d'écoute complet. */
@Serializable
data object HistoryRoute

/** Liste complète des artistes suivis (« Tes artistes »). */
@Serializable
data object FollowedArtistsRoute

/** Sous-page d'une catégorie de réglages ; [category] = `SettingsCategory.key` (l'onglet Réglages reste sélectionné). */
@Serializable
data class SettingsCategoryRoute(val category: String)
