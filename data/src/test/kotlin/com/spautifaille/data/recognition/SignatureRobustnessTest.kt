package com.spautifaille.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * La génération et l'encodage de signature ne doivent jamais lever d'exception, quelle que soit la durée de l'extrait
 * (4, 8, 12 s ou une longueur quelconque) ou son contenu (silence, bruit, sinusoïdes, signal saturé).
 */
class SignatureRobustnessTest {

    private val random = Random(42)

    private fun sine(samples: Int, hz: Double, amplitude: Double = 12_000.0) =
        ShortArray(samples) { (amplitude * sin(2 * PI * hz * it / 16_000.0)).toInt().toShort() }

    private fun noise(samples: Int, amplitude: Int) =
        ShortArray(samples) { (random.nextInt(2 * amplitude + 1) - amplitude).toShort() }

    private fun square(samples: Int, period: Int) =
        ShortArray(samples) { if ((it / period) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }

    private fun music(samples: Int) = ShortArray(samples) { n ->
        val t = n / 16_000.0
        val note = listOf(262.0, 330.0, 392.0, 523.0)[((t / 0.25).toInt()) % 4]
        (8_000 * sin(2 * PI * note * t) + 4_000 * sin(2 * PI * note * 3 * t) + random.nextInt(601) - 300).toInt().toShort()
    }

    private fun signals(samples: Int): Map<String, ShortArray> = mapOf(
        "silence" to ShortArray(samples),
        "bruit faible" to noise(samples, 50),
        "bruit plein" to noise(samples, 32_767),
        "sinus 440" to sine(samples, 440.0),
        "sinus 6 kHz" to sine(samples, 6_000.0),
        "sinus 7.9 kHz" to sine(samples, 7_900.0),
        "sinus plein" to sine(samples, 1_000.0, 32_767.0),
        "carré saturé" to square(samples, 20),
        "carré lent" to square(samples, 4_000),
        "extrême constant" to ShortArray(samples) { Short.MIN_VALUE },
        "musique" to music(samples),
    )

    private fun checkSignature(label: String, samples: Int, signature: DecodedSignature) {
        assertEquals("$label/$samples", samples, signature.numberSamples)
        signature.peaksByBand.forEach { peaks ->
            assertTrue("$label/$samples : pics non triés", peaks.zipWithNext().all { (a, b) -> a.fftPassNumber <= b.fftPassNumber })
            assertTrue(peaks.all { it.peakMagnitude in 0..0xffff && it.correctedPeakFrequencyBin in 0..0xffff })
        }
        // L'encodage (avec CRC) puis le décodage doivent fonctionner et redonner le même nombre de pics.
        // Sans aucun pic, aucune requête n'est envoyée : l'encodage n'a pas lieu d'être.
        if (signature.totalPeaks == 0) return
        val decoded = DecodedSignature.decodeFromUri(signature.encodeToUri())
        assertEquals("$label/$samples", signature.totalPeaks, decoded.totalPeaks)
        assertEquals(signature.sampleMs, decoded.sampleMs)
    }

    @Test
    fun `durees de la reconnaissance (4, 8 et 12 s) sur tous les types de signaux`() {
        for (seconds in listOf(4, 8, 12)) {
            val samples = seconds * 16_000
            for ((label, pcm) in signals(samples)) checkSignature(label, samples, SignatureGenerator.generate(pcm))
        }
    }

    @Test
    fun `longueurs quelconques, y compris non multiples d'un bloc de 128 echantillons`() {
        val lengths = listOf(0, 1, 127, 128, 129, 2_047, 2_048, 2_049, 6_000, 16_000 * 4 + 1, 16_000 * 4 + 100, 16_000 * 8 + 77, 16_000 * 12 + 1_599, 16_000 * 15 + 1)
        for (samples in lengths) {
            for ((label, pcm) in signals(samples)) checkSignature(label, samples, SignatureGenerator.generate(pcm))
        }
    }

    @Test
    fun `extraits aux longueurs aleatoires`() {
        repeat(25) {
            val samples = 16_000 * 3 + random.nextInt(16_000 * 10)
            checkSignature("musique", samples, SignatureGenerator.generate(music(samples)))
        }
    }

    @Test
    fun `un extrait plus long contient les pics de l'extrait plus court`() {
        val pcm = music(16_000 * 12)
        val short = SignatureGenerator.generate(pcm.copyOf(16_000 * 4))
        val long = SignatureGenerator.generate(pcm)
        // Les passes FFT sont causales : les pics détectés sur 4 s ne changent pas quand l'extrait s'allonge.
        short.peaksByBand.forEachIndexed { band, peaks ->
            val longPeaks = long.peaksByBand[band].toSet()
            val firstMissing = peaks.firstOrNull { it !in longPeaks }
            assertTrue("bande $band : $firstMissing absent de l'extrait long", firstMissing == null)
        }
    }
}
