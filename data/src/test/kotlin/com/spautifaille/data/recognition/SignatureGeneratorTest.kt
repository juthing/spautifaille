package com.spautifaille.data.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SignatureGeneratorTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/recognition/$name")) { "ressource $name introuvable" }.use { it.readBytes() }

    private fun referencePcm(): ShortArray {
        val bytes = ByteBuffer.wrap(resource("ref_16k_mono_s16le.pcm")).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.remaining() / 2) { bytes.getShort() }
    }

    /** Salves de sinusoïde (fréquence [hz], 80 ms toutes les 500 ms) : donne des maxima locaux dans le temps. */
    private fun toneBursts(hz: Double, seconds: Int): ShortArray = ShortArray(16_000 * seconds) { n ->
        val t = n / 16_000.0
        val phase = t % 0.5
        val envelope = if (phase < 0.08) sin(PI * phase / 0.08) else 0.0
        (12_000 * envelope * sin(2 * PI * hz * t)).toInt().toShort()
    }

    private fun frequencyHz(peak: FrequencyPeak) = peak.correctedPeakFrequencyBin * (16000.0 / 2 / 1024 / 64)

    // --- Validation contre l'implémentation Rust de référence (SongRec) -------------------------------------------

    /**
     * `ref_songrec.sig` a été produit par le code Rust de SongRec (algorithm.rs + signature_format.rs, compilés tels
     * quels hors interface graphique) à partir de `ref_16k_mono_s16le.pcm` (voir gen_ref_pcm.py).
     * La FFT et la fenêtre diffèrent légèrement (double précision / fenêtre calculée) : on exige donc un accord
     * quasi total des pics plutôt qu'une égalité binaire.
     */
    @Test
    fun `la signature correspond a celle du code Rust de SongRec`() {
        val reference = DecodedSignature.decodeFromBinary(resource("ref_songrec.sig"))
        val ours = SignatureGenerator.generate(referencePcm())

        assertEquals(reference.sampleRateHz, ours.sampleRateHz)
        assertEquals(reference.numberSamples, ours.numberSamples)
        assertEquals(reference.totalPeaks, ours.totalPeaks)
        for (band in FrequencyBand.entries) {
            val expected = reference.peaksByBand[band.ordinal]
            val actual = ours.peaksByBand[band.ordinal]
            assertEquals("pics de la bande $band", expected.size, actual.size)
            expected.zip(actual).forEachIndexed { i, (e, a) ->
                assertEquals("passe FFT, bande $band, pic $i", e.fftPassNumber, a.fftPassNumber)
                assertTrue("magnitude, bande $band, pic $i : ${e.peakMagnitude} / ${a.peakMagnitude}", abs(e.peakMagnitude - a.peakMagnitude) <= 2)
                assertTrue("fréquence, bande $band, pic $i : ${e.correctedPeakFrequencyBin} / ${a.correctedPeakFrequencyBin}", abs(e.correctedPeakFrequencyBin - a.correctedPeakFrequencyBin) <= 1)
            }
        }
    }

    @Test
    fun `reencoder la signature de reference Rust redonne exactement les memes octets`() {
        val bytes = resource("ref_songrec.sig")
        assertArrayEquals(bytes, DecodedSignature.decodeFromBinary(bytes).encodeToBinary())
    }

    // --- Format binaire ------------------------------------------------------------------------------------------

    @Test
    fun `l'en-tete binaire est conforme au format Shazam`() {
        val sig = SignatureGenerator.generate(referencePcm())
        val bytes = sig.encodeToBinary()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0xcafe2580L, bb.getInt(0).toLong() and 0xffffffffL)
        assertEquals(0x94119c00L, bb.getInt(12).toLong() and 0xffffffffL)
        assertEquals(bytes.size - 48, bb.getInt(8))
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
        assertEquals(crc, bb.getInt(4).toLong() and 0xffffffffL)
        // Taux 16 kHz = identifiant 3, décalé de 27 bits : 0x18000000.
        assertEquals(0x18000000, bb.getInt(28))
        // Nombre d'échantillons + 0.24 s d'échantillons.
        assertEquals(96_000 + 3_840, bb.getInt(40))
        assertEquals((15 shl 19) + 0x40000, bb.getInt(44))
        assertEquals(0x40000000, bb.getInt(48))
        assertEquals(bytes.size - 48, bb.getInt(52))
        // Chaque bande : identifiant 0x60030040 + n, taille, données alignées sur 4 octets.
        assertEquals(0, bytes.size % 4)
        assertEquals(0x60030040, bb.getInt(56))
    }

    @Test
    fun `encodage puis decodage redonnent la meme signature`() {
        val sig = SignatureGenerator.generate(referencePcm())
        val decoded = DecodedSignature.decodeFromBinary(sig.encodeToBinary())
        assertEquals(sig.sampleRateHz, decoded.sampleRateHz)
        assertEquals(sig.numberSamples, decoded.numberSamples)
        assertEquals(sig.peaksByBand, decoded.peaksByBand)

        val viaUri = DecodedSignature.decodeFromUri(sig.encodeToUri())
        assertEquals(sig.peaksByBand, viaUri.peaksByBand)
        assertTrue(sig.encodeToUri().startsWith("data:audio/vnd.shazam.sig;base64,"))
    }

    @Test
    fun `un saut de plus de 255 passes utilise la balise 0xff`() {
        val peaks = listOf(
            FrequencyPeak(3, 6500, 20_000),
            FrequencyPeak(10, 6600, 20_100),
            FrequencyPeak(900, 7000, 30_000), // delta 890 -> 0xff + base absolue
            FrequencyPeak(900, 7001, 30_001),
            FrequencyPeak(1_300, 7002, 30_002),
        )
        val sig = DecodedSignature(16_000, 200_000, listOf(emptyList(), peaks, emptyList(), emptyList()))
        val bytes = sig.encodeToBinary()
        assertTrue(bytes.contains(0xff.toByte()))
        val decoded = DecodedSignature.decodeFromBinary(bytes)
        assertEquals(peaks, decoded.peaksByBand[1])
        assertEquals(emptyList<FrequencyPeak>(), decoded.peaksByBand[0])
        assertEquals(200_000, decoded.numberSamples)
    }

    @Test
    fun `une signature alteree est rejetee`() {
        val bytes = SignatureGenerator.generate(referencePcm()).encodeToBinary()

        val badCrc = bytes.copyOf().also { it[bytes.size - 1] = (it[bytes.size - 1] + 1).toByte() }
        expectFormatError { DecodedSignature.decodeFromBinary(badCrc) }

        val badMagic = bytes.copyOf().also { it[0] = 0 }
        expectFormatError { DecodedSignature.decodeFromBinary(badMagic) }

        expectFormatError { DecodedSignature.decodeFromBinary(ByteArray(20)) }
        expectFormatError { DecodedSignature.decodeFromUri("data:text/plain;base64,AAAA") }
    }

    private fun expectFormatError(block: () -> Unit) {
        try {
            block()
            fail("SignatureFormatException attendue")
        } catch (_: SignatureFormatException) {
        }
    }

    // --- Comportement du générateur sur des signaux synthétiques --------------------------------------------------

    @Test
    fun `silence et extrait trop court ne donnent aucun pic`() {
        assertEquals(0, SignatureGenerator.generate(ShortArray(16_000 * 5)).totalPeaks)
        // Moins de 46 passes de 128 échantillons : la détection n'a pas commencé.
        assertEquals(0, SignatureGenerator.generate(toneBursts(1000.0, 1).copyOf(46 * 128 - 1)).totalPeaks)
    }

    @Test
    fun `des salves de 1 kHz donnent des pics regroupes dans la bonne bande et a la bonne frequence`() {
        val sig = SignatureGenerator.generate(toneBursts(1000.0, 4))
        assertEquals(64_000, sig.numberSamples)
        val inBand = sig.peaksByBand[FrequencyBand.Band520To1450.ordinal]
        assertTrue("pics attendus dans la bande 520-1450 Hz : ${inBand.size}", inBand.size >= 4)
        // Quelques pics parasites (lobes) sont tolérés, la grande majorité est autour de 1 kHz.
        val nearTone = inBand.count { abs(frequencyHz(it) - 1000.0) < 20.0 }
        assertTrue("$nearTone pics proches de 1 kHz sur ${inBand.size}", nearTone * 2 >= inBand.size)
        // Les passes sont triées (condition de l'encodage) et dans la durée de l'extrait (4 s = 500 passes).
        assertEquals(inBand.map { it.fftPassNumber }.sorted(), inBand.map { it.fftPassNumber })
        assertTrue(inBand.all { it.fftPassNumber in 0..(64_000 / 128) })
    }

    @Test
    fun `chaque bande recoit les pics de sa plage de frequences`() {
        val tones = mapOf(
            FrequencyBand.Band250To520 to 400.0,
            FrequencyBand.Band520To1450 to 900.0,
            FrequencyBand.Band1450To3500 to 2500.0,
            FrequencyBand.Band3500To5500 to 4500.0,
        )
        for ((band, hz) in tones) {
            val sig = SignatureGenerator.generate(toneBursts(hz, 3))
            val peaks = sig.peaksByBand[band.ordinal]
            assertTrue("bande $band ($hz Hz) sans pic", peaks.isNotEmpty())
            val bounds = when (band) {
                FrequencyBand.Band250To520 -> 250.0..520.0
                FrequencyBand.Band520To1450 -> 520.0..1450.0
                FrequencyBand.Band1450To3500 -> 1450.0..3500.0
                FrequencyBand.Band3500To5500 -> 3500.0..5501.0
            }
            assertTrue(peaks.all { frequencyHz(it) in bounds })
            // Des salves courtes étalent un peu d'énergie ailleurs : on exige seulement des pics sur la fréquence jouée.
            val onTone = peaks.count { abs(frequencyHz(it) - hz) < 30.0 }
            assertTrue("seulement $onTone pics autour de $hz Hz (bande $band)", onTone >= 3)
        }
    }

    @Test
    fun `le nombre de pics du signal de reference est plausible`() {
        val sig = SignatureGenerator.generate(referencePcm())
        // 6 s = 750 passes ; Shazam retient de l'ordre de quelques pics par passe au plus.
        assertTrue("${sig.totalPeaks} pics", sig.totalPeaks in 100..750 * 5)
        assertEquals(6_000, sig.sampleMs)
    }
}
