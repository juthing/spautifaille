package com.spautifaille.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/*
 * Gestion du clavier (IME) en edge-to-edge. L'activité est en edge-to-edge : la fenêtre n'est plus redimensionnée
 * par le système (sauf Android < 11 avec `adjustResize`), c'est à Compose de réserver l'espace du clavier.
 */

/**
 * Insets du contenu d'un `Scaffold` qui contient un champ de saisie : barres système **et** clavier. Le
 * `PaddingValues` reçu par le contenu inclut alors la hauteur du clavier, ce qui réduit la zone défilante
 * (la liste se redimensionne au-dessus du clavier au lieu de passer dessous). Le bas n'est pas additionné
 * (union = maximum par côté) : pas de double réservation barre de navigation + clavier.
 */
@Composable
internal fun imeAwareContentWindowInsets(): WindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime)

/**
 * Fait défiler le conteneur parent pour garder le champ visible tant qu'il a le focus et que le clavier est
 * affiché (y compris pendant l'animation d'ouverture du clavier, qui redimensionne la zone visible).
 */
@Composable
internal fun Modifier.bringIntoViewWhenFocusedWithIme(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // Lecture dans un snapshot : suit l'animation du clavier sans recomposer à chaque image.
        snapshotFlow { ime.getBottom(density) }
            .distinctUntilChanged()
            .filter { it > 0 }
            .collectLatest { requester.bringIntoView() }
    }
    return this
        .onFocusChanged { focused = it.isFocused }
        .bringIntoViewRequester(requester)
}
