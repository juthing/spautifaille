package com.spautifaille.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Espacements standards (échelle de 4 dp). À utiliser pour tous les `padding`, `Arrangement.spacedBy`
 * et `Spacer` plutôt que des valeurs en dur.
 */
@Immutable
object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 16.dp
    val l: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
}

/** Marge horizontale des écrans et de leurs en-têtes de section. */
val ScreenHorizontalPadding: Dp = 16.dp

/** Marge verticale entre deux sections d'un écran. */
val SectionSpacing: Dp = 24.dp

/** Espace à réserver sous une liste défilante pour que le dernier élément ne soit pas collé au bord. */
val ListBottomPadding: Dp = 16.dp

/** Largeur maximale du contenu sur grand écran (tablette, pliable) : au-delà, le contenu est centré. */
val ContentMaxWidth: Dp = 640.dp

/** Tailles de pochettes réutilisables. */
@Immutable
object ArtworkSize {
    /** Vignette d'une ligne de titre (`TrackListItem`). */
    val Row: Dp = 56.dp

    /** Petite carte (carrousel horizontal, mosaïque). */
    val Card: Dp = 140.dp

    /** Grande carte (à la une). */
    val CardLarge: Dp = 180.dp

    /** Avatar d'artiste dans une liste. */
    val Avatar: Dp = 48.dp

    /** Photo de profil du compte YouTube dans une barre d'application. */
    val AccountAvatar: Dp = 32.dp

    /** Photo de profil du compte YouTube dans le menu de compte et les réglages. */
    val AccountAvatarLarge: Dp = 56.dp
}
