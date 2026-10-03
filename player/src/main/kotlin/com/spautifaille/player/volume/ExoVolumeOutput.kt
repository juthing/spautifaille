package com.spautifaille.player.volume

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * [VolumeOutput] d'un [ExoPlayer] : le volume du lecteur pour les réductions, un [LoudnessEnhancer] attaché à sa
 * session audio pour les (rares, modérées) amplifications.
 *
 * Pourquoi pas un `AudioProcessor` Media3 (ou un gain de type ReplayGain intégré) :
 * - ExoPlayer n'a pas de gain par titre : seuls `Player.volume` (0..1, réduction uniquement) et les processeurs du
 *   `DefaultAudioSink` existent ;
 * - un processeur agit en AMONT des tampons de l'`AudioTrack` : changer son gain à `onMediaItemTransition`
 *   (position de lecture franchissant la frontière) ne toucherait que les échantillons suivants, soit avec
 *   plusieurs centaines de millisecondes de retard, et un changement de gain à la frontière exacte exigerait un
 *   `AudioSink` maison. `Player.volume` agit au contraire en aval, donc à la frontière ;
 * - une amplification digitale demande un limiteur pour ne pas saturer, à écrire et régler soi-même, alors que
 *   [LoudnessEnhancer] est l'effet de plateforme fait pour cela et que le gain est plafonné à
 *   `LoudnessNormalizer.MAX_BOOST_DB`.
 * Le seul défaut (l'effet vit hors du pipeline Media3, donc dépend de l'appareil) est géré par la désactivation
 * définitive ci-dessous.
 *
 * L'effet n'est créé qu'à la première amplification demandée, suit les changements de session audio du lecteur,
 * et se désactive définitivement en cas d'échec (appareil sans effet, politique OEM…) : la normalisation se
 * limite alors aux réductions, sans jamais faire échouer la lecture.
 */
class ExoVolumeOutput(private val player: ExoPlayer) : VolumeOutput, Player.Listener {

    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSessionId = C.AUDIO_SESSION_ID_UNSET
    private var boostMillibels = 0
    private var effectUnavailable = false

    fun start() {
        player.addListener(this)
    }

    fun release() {
        player.removeListener(this)
        releaseEnhancer()
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun setBoostMillibels(millibels: Int) {
        boostMillibels = millibels
        applyBoost()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        // L'effet suit la session : on le recrée sur la nouvelle si une amplification est active.
        releaseEnhancer()
        applyBoost()
    }

    private fun applyBoost() {
        if (effectUnavailable) return
        try {
            if (boostMillibels <= 0) {
                enhancer?.enabled = false
                return
            }
            val sessionId = player.audioSessionId
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
            var effect = enhancer
            if (effect == null || enhancerSessionId != sessionId) {
                releaseEnhancer()
                effect = LoudnessEnhancer(sessionId)
                enhancer = effect
                enhancerSessionId = sessionId
            }
            effect.setTargetGain(boostMillibels)
            effect.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer indisponible : normalisation limitée aux réductions", e)
            effectUnavailable = true
            releaseEnhancer()
        }
    }

    private fun releaseEnhancer() {
        try {
            enhancer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Libération du LoudnessEnhancer impossible", e)
        }
        enhancer = null
        enhancerSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private companion object {
        const val TAG = "ExoVolumeOutput"
    }
}
