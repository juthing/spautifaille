package com.spautifaille.domain.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessNormalizerTest {

    @Test
    fun `valeur absente donne gain neutre`() {
        val gain = LoudnessNormalizer.gainFor(null)
        assertEquals(PlaybackGain.Neutral, gain)
        assertTrue(gain.isNeutral)
        assertEquals(1f, gain.attenuation, 0f)
        assertEquals(0, gain.boostMillibels)
    }

    @Test
    fun `valeurs non finies ou aberrantes donne gain neutre`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 500f, -500f).forEach {
            assertTrue("$it", LoudnessNormalizer.gainFor(it).isNeutral)
        }
    }

    @Test
    fun `titre trop fort donne reduction opposee au niveau`() {
        // Valeurs réelles d'un `player` InnerTube VisionOS : « Shape of You » annonce loudnessDb = 6.35.
        val gain = LoudnessNormalizer.gainFor(6.35f)
        assertEquals(-6.35f, gain.db, 1e-4f)
        assertEquals(0.4813f, gain.attenuation, 1e-3f) // 10^(-6.35/20)
        assertEquals(0, gain.boostMillibels)
    }

    @Test
    fun `titre trop faible donne amplification moderee en millibels`() {
        val gain = LoudnessNormalizer.gainFor(-2.5f)
        assertEquals(2.5f, gain.db, 1e-4f)
        assertEquals(1f, gain.attenuation, 0f)
        assertEquals(250, gain.boostMillibels)
    }

    @Test
    fun `amplification plafonnee a MAX_BOOST_DB`() {
        // loudnessDb = -7.73 (titre très faible) demanderait +7,7 dB : on s'arrête au plafond.
        val gain = LoudnessNormalizer.gainFor(-7.73f)
        assertEquals(LoudnessNormalizer.MAX_BOOST_DB, gain.db, 0f)
        assertEquals(400, gain.boostMillibels)
    }

    @Test
    fun `reduction plafonnee a MAX_CUT_DB`() {
        val gain = LoudnessNormalizer.gainFor(25f)
        assertEquals(-LoudnessNormalizer.MAX_CUT_DB, gain.db, 0f)
        assertEquals(0.1778f, gain.attenuation, 1e-3f) // 10^(-15/20)
    }

    @Test
    fun `ecart imperceptible donne neutre`() {
        assertTrue(LoudnessNormalizer.gainFor(0.99f - 0.7f).isNeutral) // 0,29 dB < zone morte
        assertTrue(LoudnessNormalizer.gainFor(-0.2f).isNeutral)
        assertTrue(LoudnessNormalizer.gainFor(0f).isNeutral)
        assertTrue(!LoudnessNormalizer.gainFor(0.31f).isNeutral)
    }

    @Test
    fun `l attenuation reste dans 0 a 1 et la reduction ne produit jamais d amplification`() {
        var level = -80f
        while (level <= 80f) {
            val gain = LoudnessNormalizer.gainFor(level)
            assertTrue(gain.attenuation in 0f..1f)
            assertTrue(gain.db in -LoudnessNormalizer.MAX_CUT_DB..LoudnessNormalizer.MAX_BOOST_DB)
            if (gain.db < 0f) assertEquals(0, gain.boostMillibels)
            if (gain.db > 0f) assertEquals(1f, gain.attenuation, 0f)
            level += 0.37f
        }
    }

    @Test
    fun `deux titres de niveaux opposes se rapprochent du meme niveau`() {
        // Fort (+6,35 dB au-dessus de la cible) et faible (-2,5 dB) : après gain, l'écart résiduel est ~0.
        val loud = 6.35f + LoudnessNormalizer.gainFor(6.35f).db
        val quiet = -2.5f + LoudnessNormalizer.gainFor(-2.5f).db
        assertEquals(0f, loud, 1e-4f)
        assertEquals(0f, quiet, 1e-4f)
    }
}
