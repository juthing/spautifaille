package com.spautifaille.domain.loudness

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Gain de lecture à appliquer à un titre pour que tous sonnent au même niveau (« volume égal »).
 *
 * Deux canaux, car le volume du lecteur ne sait que réduire (0..1) : une réduction passe par le volume
 * ([attenuation]), une augmentation par un effet d'amplification doux ([boostMillibels]).
 */
data class PlaybackGain(val db: Float) {

    /** Facteur linéaire (0..1] à appliquer au volume du lecteur ; 1 = aucune réduction. */
    val attenuation: Float
        get() = if (db < 0f) 10.0.pow(db / 20.0).toFloat() else 1f

    /** Amplification en millibels (centièmes de dB) pour `LoudnessEnhancer` ; 0 = aucune. */
    val boostMillibels: Int
        get() = if (db > 0f) (db * 100f).roundToInt() else 0

    val isNeutral: Boolean get() = attenuation == 1f && boostMillibels == 0

    companion object {
        val Neutral = PlaybackGain(0f)
    }
}

/**
 * Calcul du gain de normalisation à partir du niveau sonore annoncé par YouTube. Fonctions pures.
 *
 * Convention : `loudnessDb` est le niveau du titre RELATIVEMENT à la cible de référence de YouTube
 * (-14 LUFS) ; positif = plus fort que la cible, négatif = plus faible. C'est la valeur `loudnessDb` des formats
 * audio du `player` InnerTube (= `trackAbsoluteLoudnessLkfs - loudnessTargetLkfs`). Le gain idéal est donc
 * son opposé.
 */
object LoudnessNormalizer {

    /** Réduction maximale (dB). Sans risque pour la qualité, large pour absorber les titres très compressés. */
    const val MAX_CUT_DB = 15f

    /**
     * Amplification maximale (dB). Volontairement modérée : on ne connaît pas le pic des titres faibles, et
     * pousser trop fort sature ou écrase la dynamique.
     */
    const val MAX_BOOST_DB = 4f

    /** Sous ce seuil (dB), l'écart est imperceptible : on ne touche à rien. */
    const val DEAD_ZONE_DB = 0.3f

    /** Valeurs au-delà desquelles l'annonce est jugée aberrante (donnée corrompue) et ignorée. */
    private const val MAX_PLAUSIBLE_ABS_DB = 60f

    /** Gain neutre pour une valeur absente, non finie ou aberrante ; sinon l'opposé de [loudnessDb], borné. */
    fun gainFor(loudnessDb: Float?): PlaybackGain {
        if (loudnessDb == null || !loudnessDb.isFinite() || abs(loudnessDb) > MAX_PLAUSIBLE_ABS_DB) {
            return PlaybackGain.Neutral
        }
        val wanted = -loudnessDb
        if (abs(wanted) < DEAD_ZONE_DB) return PlaybackGain.Neutral
        return PlaybackGain(wanted.coerceIn(-MAX_CUT_DB, MAX_BOOST_DB))
    }
}
