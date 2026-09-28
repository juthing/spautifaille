package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioStreamSelectorTest {

    private var next = 0

    private fun audio(
        bitrate: Int,
        container: AudioContainer = AudioContainer.M4A,
        itag: Int = -1,
        isUrl: Boolean = true,
        delivery: AudioDelivery = AudioDelivery.PROGRESSIVE_HTTP,
        track: AudioTrackKind? = null,
    ) = AudioCandidate(next++, bitrate, container, isUrl, delivery, track, itag)

    @Test fun emptyListGivesNull() {
        assertNull(AudioStreamSelector.select(emptyList(), AudioQuality.BEST))
    }

    @Test fun bestPicksHighestBitrate() {
        val list = listOf(audio(48), audio(160, AudioContainer.OPUS, 251), audio(128, itag = 140))
        assertEquals(1, AudioStreamSelector.select(list, AudioQuality.BEST)?.index)
    }

    @Test fun bestTieBreaksOnOpusThenM4a() {
        val m4a = audio(128, AudioContainer.M4A, 140)
        val opus = audio(128, AudioContainer.OPUS, 251)
        val other = audio(128, AudioContainer.OTHER)
        assertEquals(opus.index, AudioStreamSelector.select(listOf(m4a, other, opus), AudioQuality.BEST)?.index)
        assertEquals(m4a.index, AudioStreamSelector.select(listOf(other, m4a), AudioQuality.BEST)?.index)
    }

    @Test fun bestPrefersItag251OverOtherOpus() {
        val a = audio(160, AudioContainer.OPUS, 250)
        val b = audio(160, AudioContainer.OPUS, 251)
        assertEquals(b.index, AudioStreamSelector.select(listOf(a, b), AudioQuality.BEST)?.index)
    }

    @Test fun dataSaverPicksLowestAtLeast48() {
        val tooLow = audio(32)
        val low = audio(48)
        val mid = audio(70, AudioContainer.OPUS)
        val high = audio(160, AudioContainer.OPUS)
        assertEquals(low.index, AudioStreamSelector.select(listOf(high, tooLow, mid, low), AudioQuality.DATA_SAVER)?.index)
    }

    @Test fun dataSaverTieBreaksOnBetterCodec() {
        val m4a = audio(50, AudioContainer.M4A)
        val opus = audio(50, AudioContainer.OPUS)
        assertEquals(opus.index, AudioStreamSelector.select(listOf(m4a, opus), AudioQuality.DATA_SAVER)?.index)
    }

    @Test fun dataSaverFallsBackToBestWhenAllBelowThreshold() {
        val a = audio(24)
        val b = audio(32)
        assertEquals(b.index, AudioStreamSelector.select(listOf(a, b), AudioQuality.DATA_SAVER)?.index)
    }

    @Test fun unknownBitrateIsNeverPreferredForDataSaverNorBest() {
        val unknown = audio(AudioCandidate.UNKNOWN_BITRATE)
        val known = audio(128)
        assertEquals(known.index, AudioStreamSelector.select(listOf(unknown, known), AudioQuality.BEST)?.index)
        assertEquals(known.index, AudioStreamSelector.select(listOf(unknown, known), AudioQuality.DATA_SAVER)?.index)
    }

    @Test fun ignoresDubbedTracksWhenOriginalExists() {
        val dubbed = audio(160, AudioContainer.OPUS, track = AudioTrackKind.OTHER)
        val original = audio(128, track = AudioTrackKind.ORIGINAL)
        assertEquals(original.index, AudioStreamSelector.select(listOf(dubbed, original), AudioQuality.BEST)?.index)
    }

    @Test fun nullTrackKindIsTreatedAsOriginal() {
        val dubbed = audio(160, track = AudioTrackKind.OTHER)
        val unknownTrack = audio(64, track = null)
        assertEquals(unknownTrack.index, AudioStreamSelector.select(listOf(dubbed, unknownTrack), AudioQuality.BEST)?.index)
    }

    @Test fun keepsNonOriginalTracksWhenNoOriginalExists() {
        val dubbed = audio(128, track = AudioTrackKind.OTHER)
        assertEquals(dubbed.index, AudioStreamSelector.select(listOf(dubbed), AudioQuality.BEST)?.index)
    }

    @Test fun prefersProgressiveOverDashEvenIfDashIsBetter() {
        val dash = audio(256, isUrl = false, delivery = AudioDelivery.DASH)
        val progressive = audio(128)
        assertEquals(progressive.index, AudioStreamSelector.select(listOf(dash, progressive), AudioQuality.BEST)?.index)
    }

    @Test fun fallsBackToDashManifestWhenNoProgressiveUrl() {
        val dashLow = audio(64, isUrl = false, delivery = AudioDelivery.DASH)
        val dashHigh = audio(160, isUrl = false, delivery = AudioDelivery.DASH)
        assertEquals(dashHigh.index, AudioStreamSelector.select(listOf(dashLow, dashHigh), AudioQuality.BEST)?.index)
        assertEquals(dashLow.index, AudioStreamSelector.select(listOf(dashLow, dashHigh), AudioQuality.DATA_SAVER)?.index)
    }

    @Test fun returnsNullWhenNothingUsable() {
        val hls = audio(128, isUrl = true, delivery = AudioDelivery.OTHER)
        val urlDash = audio(128, isUrl = true, delivery = AudioDelivery.DASH)
        assertNull(AudioStreamSelector.select(listOf(hls, urlDash), AudioQuality.BEST))
    }
}
