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
