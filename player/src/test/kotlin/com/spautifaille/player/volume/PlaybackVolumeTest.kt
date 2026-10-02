package com.spautifaille.player.volume

import com.spautifaille.domain.loudness.PlaybackGain
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackVolumeTest {

    private class RecordingOutput : VolumeOutput {
        var level = 1f
        var boost = 0
        override fun setVolume(volume: Float) {
            level = volume
        }
        override fun setBoostMillibels(millibels: Int) {
            boost = millibels
        }
    }

    private val output = RecordingOutput()
    private val volume = PlaybackVolume(output)

    @Test
    fun `fondu et reduction se multiplient`() {
        volume.gain = PlaybackGain(-6f) // atténuation ≈ 0,501
        volume.fade = 0.5f
        assertEquals(0.5012f * 0.5f, output.level, 1e-3f)
        assertEquals(0, output.boost)
    }

    @Test
    fun `le fondu de la minuterie ne supprime pas le gain du titre`() {
        volume.gain = PlaybackGain(-6f)
        volume.fade = 0.2f
        volume.fade = 1f // restauration du volume après la minuterie
        assertEquals(0.5012f, output.level, 1e-3f)
    }

    @Test
    fun `un changement de gain conserve le fondu en cours`() {
        volume.fade = 0.5f
        volume.gain = PlaybackGain(-20f)
        assertEquals(0.05f, output.level, 1e-3f)
        volume.gain = PlaybackGain.Neutral
        assertEquals(0.5f, output.level, 1e-6f)
    }

    @Test
    fun `amplification transmise a l effet sans toucher au volume`() {
        volume.gain = PlaybackGain(3f)
        assertEquals(1f, output.level, 0f)
        assertEquals(300, output.boost)
        volume.gain = PlaybackGain.Neutral
        assertEquals(0, output.boost)
    }

    @Test
    fun `fondu borne entre 0 et 1`() {
        volume.fade = 7f
        assertEquals(1f, volume.fade, 0f)
        volume.fade = -2f
        assertEquals(0f, output.level, 0f)
    }
}
