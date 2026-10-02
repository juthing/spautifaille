package com.spautifaille.ui.components

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Couleurs de boutons pleins avec un état désactivé **identique** pour toutes les variantes.
 *
 * Pourquoi : les valeurs par défaut de Material 3 (1.4) diffèrent selon le type de bouton. `Button` désactivé
 * remplit son conteneur avec `onSurface` à **10 %** (`FilledButtonTokens.DisabledContainerOpacity`), alors que
 * `FilledTonalButton` utilise **12 %** : côte à côte (« Lecture » / « Aléatoire » sur une playlist vide), les deux
 * boutons n'ont pas tout à fait la même teinte. Ces fabriques imposent la valeur de la spécification M3
 * (conteneur `onSurface` 12 %, contenu `onSurface` 38 %) à `Button`, `FilledTonalButton` et `FilledTonalIconButton`.
 * Les couleurs à l'état actif restent celles de M3.
 */
object AppButtonDefaults {
    private const val DISABLED_CONTAINER_ALPHA = 0.12f
    private const val DISABLED_CONTENT_ALPHA = 0.38f

    @Composable
    private fun disabledContainer(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)

    @Composable
    private fun disabledContent(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)

    /** Pour `Button`. */
    @Composable
    fun filledColors(): ButtonColors = ButtonDefaults.buttonColors(
        disabledContainerColor = disabledContainer(),
        disabledContentColor = disabledContent(),
    )

    /** Pour `FilledTonalButton`. */
    @Composable
    fun tonalColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
        disabledContainerColor = disabledContainer(),
        disabledContentColor = disabledContent(),
    )

    /** Pour `FilledTonalIconButton`. */
    @Composable
    fun tonalIconColors(): IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
        disabledContainerColor = disabledContainer(),
        disabledContentColor = disabledContent(),
    )
}
