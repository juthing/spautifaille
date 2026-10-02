package com.spautifaille.player.volume

import com.spautifaille.domain.loudness.PlaybackGain

/** Sortie audio réelle du lecteur (volume ExoPlayer + effet d'amplification), abstraite pour les tests. */
interface VolumeOutput {
    /** Volume du lecteur, de 0 à 1 (réduction uniquement). */
    fun setVolume(volume: Float)

    /** Amplification douce en millibels (0 = aucune). */
    fun setBoostMillibels(millibels: Int)
}

/**
 * Compose les deux sources qui agissent sur le niveau de sortie, pour qu'elles ne s'écrasent pas :
 * - [fade] : le fondu de la minuterie de sommeil (0..1) ;
 * - [gain] : le gain de normalisation du titre en cours.
 *
 * Volume du lecteur = fondu × atténuation du gain ; l'amplification éventuelle va à l'effet. À utiliser depuis le
 * thread principal (celui du lecteur).
 */
class PlaybackVolume(private val output: VolumeOutput) {

    var fade: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            apply()
        }

    var gain: PlaybackGain = PlaybackGain.Neutral
        set(value) {
            field = value
            apply()
        }

    private fun apply() {
        output.setVolume((fade * gain.attenuation).coerceIn(0f, 1f))
        output.setBoostMillibels(gain.boostMillibels)
    }
}
