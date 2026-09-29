package com.spautifaille.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
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
        listOf("Accueil", "Rechercher", "Bibliothèque").forEach(::awaitNavigationLabel)
    }

    @Test
    fun navigatingBetweenTabsDoesNotCrash() {
        clickNavigation("Rechercher")
        clickNavigation("Bibliothèque")
        clickNavigation("Accueil")
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
