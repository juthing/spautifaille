/*
 * Portage Kotlin de `src/core/fingerprinting/signature_format.rs` de SongRec.
 *
 * SongRec - Copyright (C) marin-m et contributeurs - https://github.com/marin-m/SongRec
 * Licence GPL-3.0 ou ultérieure, compatible avec la licence GPL-3.0 de Spautifaille.
 * Le format binaire de signature Shazam a été rétro-conçu par le projet SongRec.
 */
package com.spautifaille.data.recognition

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32

/** Un pic spectral : numéro de passe FFT (pas de 128 échantillons), magnitude, fréquence × 64 en bins FFT. */
data class FrequencyPeak(
    val fftPassNumber: Int,
    val peakMagnitude: Int,
    val correctedPeakFrequencyBin: Int,
)

/** Bandes de fréquences de la signature (Hz). L'indice de l'enum est celui utilisé dans le format binaire. */
enum class FrequencyBand { Band250To520, Band520To1450, Band1450To3500, Band3500To5500 }

class SignatureFormatException(message: String) : IllegalArgumentException(message)

/** Signature Shazam décodée : taux d'échantillonnage, nombre d'échantillons, pics par bande. */
class DecodedSignature(
    val sampleRateHz: Int,
    val numberSamples: Int,
    /** Exactement 4 listes (une par [FrequencyBand]), pics triés par `fftPassNumber`. */
    val peaksByBand: List<List<FrequencyPeak>>,
) {
    init {
        require(peaksByBand.size == FrequencyBand.entries.size)
    }

    val totalPeaks: Int get() = peaksByBand.sumOf { it.size }

    /** Durée de l'audio en millisecondes (champ `samplems` de la requête). */
    val sampleMs: Int get() = (numberSamples.toFloat() / sampleRateHz.toFloat() * 1000f).toInt()

    fun encodeToBinary(): ByteArray {
        val out = ByteArrayOutputStream()
        val head = le(HEADER_SIZE + 8)
        head.putInt(MAGIC1)
        head.putInt(0) // crc32, écrit plus tard
        head.putInt(0) // size_minus_header, écrit plus tard
        head.putInt(MAGIC2)
        head.putInt(0).putInt(0).putInt(0) // void1
        head.putInt(sampleRateId(sampleRateHz) shl 27)
        head.putInt(0).putInt(0) // void2
        head.putInt(numberSamples + (sampleRateHz.toFloat() * 0.24f).toInt())
        head.putInt((15 shl 19) + 0x40000) // fixed_value
        head.putInt(TLV_ROOT_TAG)
        head.putInt(0) // size_minus_header (répété), écrit plus tard
        out.write(head.array())

        peaksByBand.forEachIndexed { band, peaks ->
            if (peaks.isEmpty()) return@forEachIndexed
            val payload = encodePeaks(peaks)
            val chunkHead = le(8)
            chunkHead.putInt(BAND_TAG_BASE + band)
            chunkHead.putInt(payload.size)
            out.write(chunkHead.array())
            out.write(payload)
            repeat((4 - payload.size % 4) % 4) { out.write(0) }
        }

        val buffer = out.toByteArray()
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(8, buffer.size - HEADER_SIZE)
        bb.putInt(HEADER_SIZE + 4, buffer.size - HEADER_SIZE)
        val crc = CRC32().apply { update(buffer, 8, buffer.size - 8) }
        bb.putInt(4, crc.value.toInt())
        return buffer
    }

    fun encodeToUri(): String = DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encodeToBinary())

    companion object {
        const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
        const val MAGIC1 = 0xcafe2580.toInt()
        const val MAGIC2 = 0x94119c00.toInt()
        private const val TLV_ROOT_TAG = 0x40000000
        private const val BAND_TAG_BASE = 0x60030040
        private const val HEADER_SIZE = 48

        private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        private fun sampleRateId(hz: Int): Int = when (hz) {
            8000 -> 1
            11025 -> 2
            16000 -> 3
            32000 -> 4
            44100 -> 5
            48000 -> 6
            else -> throw SignatureFormatException("Taux d'échantillonnage non supporté : $hz")
        }

        private fun sampleRateFromId(id: Int): Int = when (id) {
            1 -> 8000
            2 -> 11025
            3 -> 16000
            4 -> 32000
            5 -> 44100
            6 -> 48000
            else -> throw SignatureFormatException("Taux d'échantillonnage invalide dans la signature : $id")
        }

        /** Pics d'une bande : delta de passe sur 1 octet (0xff = nouvelle base absolue sur 4 octets), magnitude, fréquence. */
        private fun encodePeaks(peaks: List<FrequencyPeak>): ByteArray {
            val out = ByteArrayOutputStream()
            var fftPass = 0
            for (peak in peaks) {
                if (peak.fftPassNumber < fftPass) throw SignatureFormatException("Pics non triés")
                if (peak.fftPassNumber - fftPass >= 255) {
                    out.write(0xff)
                    out.write(le(4).putInt(peak.fftPassNumber).array())
                    fftPass = peak.fftPassNumber
                }
                out.write(peak.fftPassNumber - fftPass)
                out.write(le(4).putShort(peak.peakMagnitude.toShort()).putShort(peak.correctedPeakFrequencyBin.toShort()).array())
                fftPass = peak.fftPassNumber
            }
            return out.toByteArray()
        }

        fun decodeFromUri(uri: String): DecodedSignature {
            if (!uri.startsWith(DATA_URI_PREFIX)) throw SignatureFormatException("URI de signature invalide")
            return decodeFromBinary(Base64.getDecoder().decode(uri.substring(DATA_URI_PREFIX.length)))
        }

        fun decodeFromBinary(data: ByteArray): DecodedSignature {
            if (data.size <= HEADER_SIZE + 8) throw SignatureFormatException("Signature trop courte")
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic1 = bb.getInt(0)
            val crc = bb.getInt(4)
            val sizeMinusHeader = bb.getInt(8)
            val magic2 = bb.getInt(12)
            val shiftedSampleRateId = bb.getInt(28)
            val samplesPlusDivided = bb.getInt(40)
            if (magic1 != MAGIC1) throw SignatureFormatException("Magic 1 invalide")
            if (sizeMinusHeader != data.size - HEADER_SIZE) throw SignatureFormatException("Taille invalide")
            val computed = CRC32().apply { update(data, 8, data.size - 8) }.value.toInt()
            if (crc != computed) throw SignatureFormatException("CRC32 invalide")
            if (magic2 != MAGIC2) throw SignatureFormatException("Magic 2 invalide")

            val sampleRate = sampleRateFromId(shiftedSampleRateId ushr 27)
            val numberSamples = samplesPlusDivided - (sampleRate.toFloat() * 0.24f).toInt()

            if (bb.getInt(HEADER_SIZE) != TLV_ROOT_TAG) throw SignatureFormatException("En-tête TLV invalide")
            if (bb.getInt(HEADER_SIZE + 4) != data.size - HEADER_SIZE) throw SignatureFormatException("Taille TLV invalide")

            val bands = List(FrequencyBand.entries.size) { mutableListOf<FrequencyPeak>() }
            var pos = HEADER_SIZE + 8
            while (pos < data.size) {
                if (pos + 8 > data.size) throw SignatureFormatException("Bloc tronqué")
                val bandId = bb.getInt(pos) - BAND_TAG_BASE
                val size = bb.getInt(pos + 4)
                pos += 8
                if (bandId !in bands.indices) throw SignatureFormatException("Bande de fréquences invalide")
                if (size < 0 || pos + size > data.size) throw SignatureFormatException("Bloc de pics tronqué")
                var p = pos
                val end = pos + size
                var fftPass = 0
                while (p < end) {
                    val offset = data[p++].toInt() and 0xff
                    if (offset == 0xff) {
                        if (p + 4 > end) throw SignatureFormatException("Pic tronqué")
                        fftPass = bb.getInt(p)
                        p += 4
                    } else {
                        if (p + 4 > end) throw SignatureFormatException("Pic tronqué")
                        fftPass += offset
                        bands[bandId] += FrequencyPeak(
                            fftPassNumber = fftPass,
                            peakMagnitude = bb.getShort(p).toInt() and 0xffff,
                            correctedPeakFrequencyBin = bb.getShort(p + 2).toInt() and 0xffff,
                        )
                        p += 4
                    }
                }
                pos = end + (4 - size % 4) % 4
            }
            return DecodedSignature(sampleRate, numberSamples, bands)
        }
    }
}
