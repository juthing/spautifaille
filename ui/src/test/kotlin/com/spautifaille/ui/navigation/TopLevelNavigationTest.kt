package com.spautifaille.ui.navigation

import android.content.Context
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.domain.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Onglet sélectionné pendant de vraies navigations (graphe plat comme dans l'app, `saveState` / `restoreState`
 * via [navigateToTopLevel]) : le scénario du bug « Réglages > Apparence > Accueil > Réglages » et ses variantes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TopLevelNavigationTest {

    private lateinit var nav: TestNavHostController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        nav = TestNavHostController(context)
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.setGraph(
            nav.createGraph(startDestination = HomeRoute) {
                composable<HomeRoute> {}
                composable<SearchRoute> {}
                composable<LibraryRoute> {}
                composable<PlaylistRoute> {}
                composable<ArtistRoute> {}
                composable<SettingsRoute> {}
                composable<SettingsCategoryRoute> {}
                composable<DownloadsRoute> {}
                composable<ImportRoute> {}
            },
            null,
        )
    }

    private fun selected(): TopLevelDestination = nav.selectedTopLevel()

    private fun tab(destination: TopLevelDestination) {
        nav.navigateToTopLevel(destination, reselected = destination == selected())
    }

    @Test
    fun `l accueil est selectionne au demarrage`() {
        assertEquals(TopLevelDestination.Home, selected())
    }

    @Test
    fun `sans onglet racine dans la pile l accueil est selectionne`() {
        assertEquals(TopLevelDestination.Home, selectedTopLevel { false })
        assertEquals(TopLevelDestination.Settings, selectedTopLevel { it == TopLevelDestination.Settings })
    }

    @Test
    fun `reglages - sous page - accueil - retour aux reglages`() {
        tab(TopLevelDestination.Settings)
        nav.navigate(SettingsCategoryRoute("appearance"))
        assertEquals(TopLevelDestination.Settings, selected())

        tab(TopLevelDestination.Home)
        assertEquals(TopLevelDestination.Home, selected())

        tab(TopLevelDestination.Settings)
        assertEquals(TopLevelDestination.Settings, selected())
    }

    @Test
    fun `bibliotheque - playlist - autre onglet - retour`() {
        tab(TopLevelDestination.Library)
        nav.navigate(PlaylistRoute(Playlist.LIKED_ID))
        assertEquals(TopLevelDestination.Library, selected())

        tab(TopLevelDestination.Settings)
        assertEquals(TopLevelDestination.Settings, selected())

        tab(TopLevelDestination.Library)
        assertEquals(TopLevelDestination.Library, selected())
        assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<PlaylistRoute>())
    }

    @Test
    fun `accueil - playlist - autre onglet - retour a l accueil`() {
        nav.navigate(PlaylistRoute(1))
        assertEquals(TopLevelDestination.Home, selected())

        tab(TopLevelDestination.Library)
        assertEquals(TopLevelDestination.Library, selected())

        tab(TopLevelDestination.Home)
        assertEquals(TopLevelDestination.Home, selected())
    }

    @Test
    fun `une sous page ouverte depuis un onglet reste rattachee a cet onglet`() {
        tab(TopLevelDestination.Library)
        nav.navigate(ArtistRoute("https://youtube.com/channel/x"))
        assertEquals(TopLevelDestination.Library, selected())

        tab(TopLevelDestination.Settings)
        nav.navigate(SettingsCategoryRoute("storage"))
        nav.navigate(DownloadsRoute)
        assertEquals(TopLevelDestination.Settings, selected())

        nav.navigate(ImportRoute)
        assertEquals(TopLevelDestination.Settings, selected())
    }

    @Test
    fun `le retour systeme ramene a l accueil et met a jour l onglet`() {
        tab(TopLevelDestination.Library)
        nav.navigate(PlaylistRoute(1))
        nav.popBackStack()
        assertEquals(TopLevelDestination.Library, selected())
        nav.popBackStack()
        assertEquals(TopLevelDestination.Home, selected())

        tab(TopLevelDestination.Settings)
        nav.popBackStack()
        assertEquals(TopLevelDestination.Home, selected())
    }

    @Test
    fun `retoucher l onglet courant revient a sa racine`() {
        tab(TopLevelDestination.Settings)
        nav.navigate(SettingsCategoryRoute("appearance"))
        tab(TopLevelDestination.Settings) // déjà sélectionné : retour à la racine de l'onglet
        assertEquals(TopLevelDestination.Settings, selected())
        assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<SettingsRoute>())
    }

    @Test
    fun `recherche depuis l accueil reste sur l accueil`() {
        nav.navigate(SearchRoute)
        assertEquals(TopLevelDestination.Home, selected())
    }
}
