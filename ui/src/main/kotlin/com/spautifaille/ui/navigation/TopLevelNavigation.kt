package com.spautifaille.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/**
 * Onglet sélectionné, dérivé de la pile de navigation (et non d'un état mémorisé à part qui pourrait diverger) :
 * l'onglet racine présent dans la pile, ou l'Accueil (racine du graphe) sinon. [navigateToTopLevel] ne garde
 * jamais plus d'un onglet autre que l'Accueil dans la pile ; les sous-pages (catégorie de réglages, playlist,
 * artiste, import...) restent donc rattachées à l'onglet depuis lequel on les a ouvertes, y compris après
 * `saveState` / `restoreState` (restauration de la pile d'un onglet) ou la mort du processus.
 */
internal fun selectedTopLevel(isOnBackStack: (TopLevelDestination) -> Boolean): TopLevelDestination =
    TopLevelDestination.entries.firstOrNull { it != TopLevelDestination.Home && isOnBackStack(it) }
        ?: TopLevelDestination.Home

/**
 * [selectedTopLevel] pour la pile courante (API publique `getBackStackEntry`, qui lève [IllegalArgumentException]
 * si la route est absente de la pile et [IllegalStateException] tant que le graphe n'est pas posé, c'est-à-dire
 * à la toute première composition, avant que `NavHost` n'ait appelé `setGraph`).
 */
internal fun NavController.selectedTopLevel(): TopLevelDestination = selectedTopLevel { destination ->
    try {
        getBackStackEntry(destination.route)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}

/**
 * Bascule vers un onglet en conservant la pile de l'onglet quitté (`saveState`) et en restaurant celle de la
 * destination (`restoreState`). Re-toucher l'onglet courant revient à sa racine.
 */
internal fun NavHostController.navigateToTopLevel(destination: TopLevelDestination, reselected: Boolean) {
    if (reselected) {
        popBackStack(destination.route, inclusive = false)
        return
    }
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
