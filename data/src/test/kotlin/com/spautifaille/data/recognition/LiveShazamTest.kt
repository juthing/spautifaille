package com.spautifaille.data.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test réseau réel contre l'endpoint non officiel de Shazam. Désactivé par défaut ; pour le lancer :
 * `SPAUTIFAILLE_LIVE_TESTS=1 ./gradlew :data:testDebugUnitTest --tests "*LiveShazamTest*"`
 *
 * Un signal synthétique ne correspond à aucun titre : le test vérifie seulement que la requête est acceptée
 * (pas d'exception, réponse JSON analysable). Pour tester une vraie reconnaissance, définir
 * `SPAUTIFAILLE_LIVE_PCM` vers un fichier PCM 16 kHz mono 16 bits little-endian d'un extrait de morceau (10 à 12 s).
 */
class LiveShazamTest {

    private val recognizer = ShazamMusicRecognizer(OkHttpClient(), Dispatchers.Default)

    @Before fun onlyWhenEnabled() {
        assumeTrue(System.getenv("SPAUTIFAILLE_LIVE_TESTS") == "1")
    }

    @Test fun endpointAcceptsASignature() = runBlocking {
        val path = System.getenv("SPAUTIFAILLE_LIVE_PCM")
        val pcm = if (path != null) {
            val bytes = ByteBuffer.wrap(java.io.File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(bytes.remaining() / 2) { bytes.getShort() }
        } else {
            val bytes = ByteBuffer.wrap(javaClass.getResourceAsStream("/recognition/ref_16k_mono_s16le.pcm")!!.readBytes())
                .order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(bytes.remaining() / 2) { bytes.getShort() }
        }
        val track = recognizer.recognize(pcm, 16_000)
        println("shazam=$track")
    }
}
