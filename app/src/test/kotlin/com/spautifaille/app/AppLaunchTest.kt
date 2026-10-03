package com.spautifaille.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.media3.session.MediaLibraryService
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.spautifaille.player.PlaybackService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Smoke test JVM : valide le graphe Hilt de production complet (data + player + ui + app), le démarrage de
 * `MainActivity` et le rendu Compose de l'écran d'accueil, puis la navigation entre les onglets.
 *
 * Seul `StreamRepository` est remplacé (aucun accès réseau, voir [TestStreamModule]). WorkManager est initialisé
 * avec la fabrique Hilt, comme le fait `SpautifailleApp` en production (`HiltTestApplication` n'est pas un
 * `Configuration.Provider`).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
@OptIn(ExperimentalTestApi::class)
class AppLaunchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var workerFactory: HiltWorkerFactory

    private var service: PlaybackService? = null

    /** S'exécute avant le lancement de l'activité (les règles s'exécutent par ordre croissant). */
    @get:Rule(order = 1)
    val environmentRule = object : ExternalResource() {
        override fun before() {
            hiltRule.inject()
            val context = ApplicationProvider.getApplicationContext<Application>()
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setWorkerFactory(workerFactory).build(),
            )
            // Robolectric ne démarre pas les services liés tout seul : on crée le vrai PlaybackService (injecté
            // par Hilt) et on l'expose au MediaController de PlaybackControllerImpl.
            val playbackService = Robolectric.buildService(PlaybackService::class.java).create().get()
            val binder = playbackService.onBind(Intent(MediaLibraryService.SERVICE_INTERFACE))
            shadowOf(context).setComponentNameAndServiceForBindService(
                ComponentName(context, PlaybackService::class.java),
                binder,
            )
            service = playbackService
        }

        override fun after() {
            service?.onDestroy()
            service = null
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun awaitNavigationLabel(label: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickNavigation(label: String) {
        awaitNavigationLabel(label)
        composeRule.onAllNodes(hasClickAction() and hasText(label)).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodes(isSelected() and hasText(label)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun homeRendersWithBottomNavigation() {
        listOf("Accueil", "Bibliothèque", "Réglages").forEach(::awaitNavigationLabel)
        // La recherche n'est plus un onglet : elle s'ouvre depuis la barre de l'Accueil.
        composeRule.onAllNodes(hasText("Rechercher")).assertCountEquals(0)
    }

    @Test
    fun accountAvatarIsOfferedOnHomeAndLibraryWhenSignedOut() {
        // Sans compte : l'icône de connexion est dans la barre d'application, l'app fonctionne comme avant.
        awaitNavigationLabel("Accueil")
        composeRule.onAllNodes(hasContentDescription("Se connecter à YouTube")).assertCountEquals(1)
        clickNavigation("Bibliothèque")
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodes(hasContentDescription("Se connecter à YouTube")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun navigatingBetweenTabsDoesNotCrash() {
        clickNavigation("Bibliothèque")
        clickNavigation("Réglages")
        clickNavigation("Accueil")
    }

    @Test
    fun searchOpensFromHomeKeepingHomeSelectedAndBackReturns() {
        awaitNavigationLabel("Accueil")
        composeRule.onAllNodes(hasClickAction() and hasText(SEARCH_PLACEHOLDER)).onFirst().performClick()
        // Écran de recherche : flèche de retour présente, onglet Accueil toujours sélectionné.
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodes(hasContentDescription("Retour")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(isSelected() and hasText("Accueil")).assertCountEquals(1)
        // Retour système : retour à l'Accueil.
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodes(hasContentDescription("Retour")).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val SEARCH_PLACEHOLDER = "Titres, artistes, albums…"
    }
}
