package com.spautifaille.data.newpipe

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Les fichiers `newpipe/player_visionos_*.json` sont de vraies réponses `youtubei/v1/player` du client VisionOS
 * (récupérées le 2026-10-02, voir leur clé `_provenance`), réduites aux champs utiles.
 */
class PlayerResponseLoudnessTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("newpipe/$name")!!.bufferedReader().use { it.readText() }

    @Test fun `reponse reelle proche de la cible`() {
        // trackAbsoluteLoudnessLkfs -13.01, cible -14 -> +0.99 ; les formats annoncent loudnessDb 0.98..0.99.
        val result = PlayerResponseLoudness.parse(fixture("player_visionos_near_target.json"))!!
        assertEquals("dQw4w9WgXcQ", result.videoId)
        assertEquals(0.99f, result.loudnessDb, 0.011f)
    }

    @Test fun `reponse reelle d un titre fort`() {
        val result = PlayerResponseLoudness.parse(fixture("player_visionos_loud.json"))!!
        assertEquals("JGwWNGJdvx8", result.videoId)
        assertEquals(6.35f, result.loudnessDb, 0.011f)
    }

    @Test fun `reponse reelle d un titre faible donne une valeur negative`() {
        val result = PlayerResponseLoudness.parse(fixture("player_visionos_quiet.json"))!!
        assertEquals("CvFH_6DNRCY", result.videoId)
        assertEquals(-7.73f, result.loudnessDb, 0.011f)
    }

    @Test fun `le niveau par audioConfig correspond a celui des formats audio`() {
        // Cohérence des deux sources sur les trois réponses réelles : écart < 0,02 dB.
        listOf("near_target", "loud", "quiet").forEach { label ->
            val body = fixture("player_visionos_$label.json")
            val fromConfig = PlayerResponseLoudness.parse(body)!!.loudnessDb
            val withoutConfig = body.replace("\"audioConfig\"", "\"audioConfigX\"")
            val fromFormats = PlayerResponseLoudness.parse(withoutConfig)!!.loudnessDb
            assertEquals(label, fromConfig, fromFormats, 0.02f)
        }
    }

    @Test fun `ancien champ loudnessDb de audioConfig prioritaire`() {
        val body = """{"videoDetails":{"videoId":"abc"},"playerConfig":{"audioConfig":{"loudnessDb":-3.5,"perceptualLoudnessDb":-9}}}"""
        assertEquals(-3.5f, PlayerResponseLoudness.parse(body)!!.loudnessDb, 0f)
    }

    @Test fun `niveau absolu seul compare a la cible par defaut -14`() {
        val body = """{"videoDetails":{"videoId":"abc"},"playerConfig":{"audioConfig":{"perceptualLoudnessDb":-10.5}}}"""
        assertEquals(3.5f, PlayerResponseLoudness.parse(body)!!.loudnessDb, 1e-4f)
    }

    @Test fun `cible annoncee par la reponse`() {
        val body = """{"videoDetails":{"videoId":"abc"},"playerConfig":{"audioConfig":{"trackAbsoluteLoudnessLkfs":-10,"loudnessTargetLkfs":-16}}}"""
        assertEquals(6f, PlayerResponseLoudness.parse(body)!!.loudnessDb, 1e-4f)
    }

    @Test fun `repli sur le loudnessDb d un format audio, jamais d un format video`() {
        val body = """{"videoDetails":{"videoId":"abc"},"streamingData":{"adaptiveFormats":[
            {"itag":137,"mimeType":"video/mp4; codecs=\"avc1\"","loudnessDb":9.0},
            {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","loudnessDb":2.25}]}}"""
        assertEquals(2.25f, PlayerResponseLoudness.parse(body)!!.loudnessDb, 0f)
    }

    @Test fun `champs absents ou invalides donnent null sans exception`() {
        listOf(
            "",
            "pas du json loudness",
            "[]",
            "{}",
            """{"videoDetails":{"videoId":"abc"}}""",
            """{"videoDetails":{"videoId":"abc"},"playerConfig":{"audioConfig":{}}}""",
            """{"videoDetails":{"videoId":"abc"},"playerConfig":{"audioConfig":{"loudnessDb":"fort"}}}""",
            """{"videoDetails":{"videoId":"abc"},"playerConfig":{"audioConfig":"loudness"}}""",
            """{"playerConfig":{"audioConfig":{"loudnessDb":1.0}}}""",
            """{"videoDetails":{"videoId":""},"playerConfig":{"audioConfig":{"loudnessDb":1.0}}}""",
            """{"videoDetails":{"videoId":"abc"},"streamingData":{"adaptiveFormats":"loudness"}}""",
            """{"videoDetails":{"videoId":"abc"},"streamingData":{"adaptiveFormats":[1,null,{"mimeType":"audio/mp4"}]}}""",
        ).forEach { assertNull(it, PlayerResponseLoudness.parse(it)) }
    }

    @Test fun `le recorder ne memorise que les reponses player`() {
        val store = FakeLoudnessStore()
        val recorder = PlayerLoudnessRecorder(store)
        val body = fixture("player_visionos_loud.json")

        recorder.onResponse("https://www.youtube.com/youtubei/v1/search?x=1", body)
        assertEquals(emptyMap<String, Float>(), store.values)

        recorder.onResponse("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&id=JGwWNGJdvx8", body)
        assertEquals(6.35f, store.values.getValue("JGwWNGJdvx8"), 0.011f)
    }

    @Test fun `le recorder ignore un corps illisible`() {
        val store = FakeLoudnessStore()
        PlayerLoudnessRecorder(store).onResponse("https://x/youtubei/v1/player", "{ loudness")
        assertEquals(emptyMap<String, Float>(), store.values)
    }

    @Test fun `le Downloader releve le niveau sonore sans alterer la reponse`() {
        val body = fixture("player_visionos_quiet.json")
        val store = FakeLoudnessStore()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(body).build())
            server.enqueue(MockResponse.Builder().code(500).body(body).build())
            val downloader = OkHttpDownloader(OkHttpClient(), PlayerLoudnessRecorder(store))

            val ok = downloader.get(server.url("/youtubei/v1/player").toString())
            assertEquals(body, ok.responseBody())
            assertEquals(-7.73f, store.values.getValue("CvFH_6DNRCY"), 0.011f)

            // Une réponse en erreur n'est pas exploitée.
            store.values.clear()
            val failed = downloader.get(server.url("/youtubei/v1/player").toString())
            assertEquals(500, failed.responseCode())
            assertEquals(emptyMap<String, Float>(), store.values)
        }
    }
}
