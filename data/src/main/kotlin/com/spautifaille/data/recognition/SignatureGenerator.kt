/*
 * Portage Kotlin de `src/core/fingerprinting/algorithm.rs` de SongRec.
 *
 * SongRec - Copyright (C) marin-m et contributeurs - https://github.com/marin-m/SongRec
 * Licence GPL-3.0 ou ultérieure, compatible avec la licence GPL-3.0 de Spautifaille.
 *
 * Différences volontaires avec l'original :
 *  - l'entrée est directement du PCM 16 bits (le décodage de fichiers n'est pas porté) ;
 *  - la FFT (réelle, 2048 points) est calculée en double précision par un radix-2 maison, là où SongRec utilise
 *    `realfft` en simple précision ;
 *  - la fenêtre de Hanning est calculée (`hanning(2050)[1:-1]`, comme dans la version Python de SongRec) au lieu
 *    d'être lue dans la table de 2048 valeurs arrondies à 5 chiffres significatifs.
 */
package com.spautifaille.data.recognition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Génère la signature Shazam d'un extrait PCM 16 kHz mono 16 bits : spectrogramme (FFT 2048 avec fenêtre de Hanning,
 * un pas toutes les 128 échantillons), étalement des pics en fréquence puis en temps, détection des maxima locaux,
 * regroupement en 4 bandes de fréquences.
 *
 * Non thread-safe : utiliser une instance par génération (l'état interne est un ensemble de tampons circulaires).
 */
class SignatureGenerator private constructor() {

    private val ringOfSamples = ShortArray(FFT_SIZE)
    private var ringIndex = 0
    private val windowed = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)

    /** 256 derniers spectres de puissance (1025 bins). */
    private val fftOutputs = Array(RING) { FloatArray(BINS) }
    private var fftIndex = 0

    private val spreadOutputs = Array(RING) { FloatArray(BINS) }
    private var spreadIndex = 0
    private var numSpreadFftsDone = 0

    private val peaks = List(FrequencyBand.entries.size) { mutableListOf<FrequencyPeak>() }

    private fun generate(pcm: ShortArray): DecodedSignature {
        var offset = 0
        while (offset + HOP <= pcm.size) {
            doFft(pcm, offset)
            doPeakSpreading()
            numSpreadFftsDone++
            if (numSpreadFftsDone >= 46) doPeakRecognition()
            offset += HOP
        }
        return DecodedSignature(SAMPLE_RATE, pcm.size, peaks)
    }

    private fun doFft(pcm: ShortArray, offset: Int) {
        System.arraycopy(pcm, offset, ringOfSamples, ringIndex, HOP)
        ringIndex = (ringIndex + HOP) and (FFT_SIZE - 1)

        // Dernières données en fin de tableau, fenêtre de Hanning appliquée (en simple précision, comme SongRec).
        for (i in 0 until FFT_SIZE) {
            windowed[i] = (ringOfSamples[(i + ringIndex) and (FFT_SIZE - 1)].toFloat() * HANNING[i]).toDouble()
        }
        imag.fill(0.0)
        fft(windowed, imag)

        val out = fftOutputs[fftIndex]
        for (bin in 0 until BINS) {
            val power = (windowed[bin] * windowed[bin] + imag[bin] * imag[bin]).toFloat() / POWER_SCALE
            out[bin] = max(power, MIN_POWER)
        }
        fftIndex = (fftIndex + 1) and (RING - 1)
    }

    private fun doPeakSpreading() {
        val real = fftOutputs[(fftIndex - 1) and (RING - 1)]
        val spread = spreadOutputs[spreadIndex]

        // Étalement en fréquence.
        real.copyInto(spread)
        for (position in 0..1022) {
            spread[position] = max(max(spread[position], spread[position + 1]), spread[position + 2])
        }

        // Étalement dans le temps : on reporte le spectre étalé sur les 1, 3 et 6 passes précédentes.
        for (former in FORMER_SPREADS) {
            val target = spreadOutputs[(spreadIndex - former) and (RING - 1)]
            for (position in 0 until BINS) {
                target[position] = max(target[position], spread[position])
            }
        }
        spreadIndex = (spreadIndex + 1) and (RING - 1)
    }

    private fun doPeakRecognition() {
        val fftMinus46 = fftOutputs[(fftIndex - 46) and (RING - 1)]
        val fftMinus49 = spreadOutputs[(spreadIndex - 49) and (RING - 1)]

        for (bin in 10..1014) {
            val value = fftMinus46[bin]
            // Assez fort pour être un pic.
            if (value < MIN_PEAK_POWER || value < fftMinus49[bin - 1]) continue

            // Maximum local en fréquence.
            var maxNeighbor = 0f
            for (neighborOffset in FREQUENCY_NEIGHBORS) {
                maxNeighbor = max(maxNeighbor, fftMinus49[bin + neighborOffset])
            }
            if (value <= maxNeighbor) continue

            // Maximum local dans le temps.
            var maxOther = maxNeighbor
            for (otherOffset in TIME_NEIGHBORS) {
                val other = spreadOutputs[(spreadIndex + otherOffset) and (RING - 1)]
                maxOther = max(maxOther, other[bin - 1])
            }
            if (value <= maxOther) continue

            val fftPassNumber = numSpreadFftsDone - 46

            val magnitude = logMagnitude(value)
            val magnitudeBefore = logMagnitude(fftMinus46[bin - 1])
            val magnitudeAfter = logMagnitude(fftMinus46[bin + 1])

            val variation1 = magnitude * 2f - magnitudeBefore - magnitudeAfter
            // SongRec fait un `assert!(variation1 >= 0)` (panique) : on ignore plutôt le pic.
            if (!(variation1 >= 0f)) continue
            val variation2 = (magnitudeAfter - magnitudeBefore) * 32f / variation1

            val correctedBin = ((bin * 64) + variation2.toInt()) and 0xffff

            // Bin FFT -> Hz (16 kHz, 1024 bins utiles, ×64 appliqué ci-dessus).
            val frequencyHz = correctedBin.toFloat() * (16000f / 2f / 1024f / 64f)
            val band = when (frequencyHz.toInt()) {
                in 250..519 -> FrequencyBand.Band250To520
                in 520..1449 -> FrequencyBand.Band520To1450
                in 1450..3499 -> FrequencyBand.Band1450To3500
                in 3500..5500 -> FrequencyBand.Band3500To5500
                else -> continue
            }
            peaks[band.ordinal] += FrequencyPeak(
                fftPassNumber = fftPassNumber,
                peakMagnitude = magnitude.toInt().coerceIn(0, 0xffff),
                correctedPeakFrequencyBin = correctedBin,
            )
        }
    }

    private fun logMagnitude(power: Float): Float = max(ln(power), MIN_PEAK_POWER) * 1477.3f + 6144f

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FFT_SIZE = 2048
        private const val BINS = FFT_SIZE / 2 + 1
        private const val HOP = 128
        private const val RING = 256
        private const val POWER_SCALE = (1 shl 17).toFloat()
        private const val MIN_POWER = 0.0000000001f
        private const val MIN_PEAK_POWER = 1f / 64f
        private val FORMER_SPREADS = intArrayOf(1, 3, 6)
        private val FREQUENCY_NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val TIME_NEIGHBORS =
            intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

        /** `hanning(2050)[1:-1]` : Hanning sans les deux zéros des bords. */
        private val HANNING = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * (i + 1) / (FFT_SIZE + 1))).toFloat()
        }

        private val COS = DoubleArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE) }
        private val SIN = DoubleArray(FFT_SIZE / 2) { -sin(2.0 * PI * it / FFT_SIZE) }
        private val BIT_REVERSE = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - 11) }

        /**
         * Signature d'un extrait PCM 16 kHz mono. Comme dans SongRec, les derniers échantillons qui ne remplissent
         * pas un bloc de 128 sont ignorés.
         */
        fun generate(pcm16kMono: ShortArray): DecodedSignature = SignatureGenerator().generate(pcm16kMono)

        /** FFT complexe radix-2 en place (temps -> fréquence, sans normalisation). */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until FFT_SIZE) {
                val j = BIT_REVERSE[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var half = 1
            while (half < FFT_SIZE) {
                val step = FFT_SIZE / (half * 2)
                var start = 0
                while (start < FFT_SIZE) {
                    for (k in 0 until half) {
                        val wr = COS[k * step]
                        val wi = SIN[k * step]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                    start += half * 2
                }
                half *= 2
            }
        }
    }
}
