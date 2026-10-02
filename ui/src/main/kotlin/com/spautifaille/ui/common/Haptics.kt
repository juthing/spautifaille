package com.spautifaille.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Retours haptiques sémantiques de l'application : on exprime l'intention (« confirmer », « refuser »,
 * « bascule »…) et non le type de vibration, que ce wrapper traduit en [HapticFeedbackType].
 *
 * Obtenir l'instance partagée avec `val haptics = LocalAppHaptics.current` (fournie par `SpautifailleAppUi`).
 * Dans une preview ou un test sans fournisseur, l'instance par défaut ne fait rien.
 *
 * Règles d'usage :
 * - [click] : toucher ordinaire sur un bouton / une ligne / un onglet ;
 * - [toggle] : interrupteur, case à cocher, bouton j'aime, shuffle, repeat… ;
 * - [confirm] / [reject] : fin d'action réussie (playlist créée, import terminé) / refusée ou en erreur ;
 * - [longPress] : appui long ayant déclenché une action (menu contextuel, mode sélection) ;
 * - [tick] / [frequentTick] : crans discrets d'un slider ou d'un contrôle segmenté, d'un réordonnancement ;
 * - [gestureThreshold] : un geste (glisser pour supprimer, tirer pour rafraîchir) vient de franchir son seuil.
 *
 * Ne pas déclencher d'haptique à chaque recomposition ni à haute fréquence continue (préférer [tick] aux
 * changements de valeur entière seulement).
 */
@Stable
class AppHaptics internal constructor(private val feedback: HapticFeedback?) {

    /** Toucher ordinaire (bouton, ligne, onglet). */
    fun click() = perform(HapticFeedbackType.VirtualKey)

    /** Bascule d'un état binaire : [on] = nouvel état. */
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** Action menée à bien. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    /** Action refusée ou échouée. */
    fun reject() = perform(HapticFeedbackType.Reject)

    /** Appui long reconnu. */
    fun longPress() = perform(HapticFeedbackType.LongPress)

    /** Cran discret (valeur de slider, segment, élément déplacé d'un rang). */
    fun tick() = perform(HapticFeedbackType.SegmentTick)

    /** Cran discret pour une succession rapide de valeurs (légèrement plus discret que [tick]). */
    fun frequentTick() = perform(HapticFeedbackType.SegmentFrequentTick)

    /** Un geste vient de franchir son seuil d'activation. */
    fun gestureThreshold() = perform(HapticFeedbackType.GestureThresholdActivate)

    private fun perform(type: HapticFeedbackType) {
        feedback?.performHapticFeedback(type)
    }

    companion object {
        /** Instance inerte (previews, tests). */
        val None: AppHaptics = AppHaptics(null)
    }
}

/** Instance partagée, fournie une fois par `SpautifailleAppUi`. Par défaut : inerte. */
val LocalAppHaptics = compositionLocalOf { AppHaptics.None }

/** Crée un [AppHaptics] adossé au `LocalHapticFeedback` courant. Réservé au point de fourniture de [LocalAppHaptics]. */
@Composable
fun rememberAppHaptics(): AppHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { AppHaptics(feedback) }
}
