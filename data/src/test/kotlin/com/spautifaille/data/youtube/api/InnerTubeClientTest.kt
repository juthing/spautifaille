package com.spautifaille.data.youtube.api

import com.spautifaille.data.youtube.FakeSessionStore
import com.spautifaille.data.youtube.testCredentials
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.AccountState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InnerTubeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var sessions: FakeSessionStore
    private lateinit var client: InnerTubeClient

    // 2023-11-14T22:13:20Z
    private val nowMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        sessions = FakeSessionStore()
        client = InnerTubeClient(
            http = OkHttpClient(),
            sessions = sessions,
            io = Dispatchers.Default,
            clock = { nowMs },
            baseUrl = server.url("/youtubei/v1/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private suspend fun appError(block: suspend () -> Unit): AppException {
        try {
            block()
        } catch (e: AppException) {
            return e
        }
        throw AssertionError("AppException attendue")
    }

    private fun ok(body: String = """{"ok":true}""") =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun error(code: Int, body: String = "") =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `la requete porte les en-tetes d authentification et le contexte WEB_REMIX`() = runTest {
        server.enqueue(ok())

        client.post("like/like", buildJsonObject { put("hint", "x") })

        val request = server.takeRequest()
        assertEquals("/youtubei/v1/like/like?prettyPrint=false", request.url.encodedPath + "?" + request.url.query)
        assertEquals("SAPISIDHASH 1700000000_7b256a560598a3c89560973a2bd6a5f8a465b023", request.headers["Authorization"])
        assertEquals("https://music.youtube.com", request.headers["Origin"])
        assertEquals("https://music.youtube.com", request.headers["X-Origin"])
        assertEquals("0", request.headers["X-Goog-AuthUser"])
        assertEquals("VISITOR", request.headers["X-Goog-Visitor-Id"])
        assertEquals("67", request.headers["X-YouTube-Client-Name"])
        assertEquals("1.20231114.01.00", request.headers["X-YouTube-Client-Version"])
        val cookie = request.headers["Cookie"]!!
        assertTrue(cookie.contains("SAPISID=AbCdEf123_sapisid-VALUE"))
        assertTrue("consentement", cookie.contains("SOCS=CAI"))

        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("x", body["hint"]!!.jsonPrimitive.content)
        val context = body["context"]!!.jsonObject
        assertEquals("WEB_REMIX", context["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
        assertEquals("1.20231114.01.00", context["client"]!!.jsonObject["clientVersion"]!!.jsonPrimitive.content)
        assertEquals("VISITOR", context["client"]!!.jsonObject["visitorData"]!!.jsonPrimitive.content)
        // `DATASYNC_ID` « 1234567890|| » : seule la partie avant « || » est envoyée.
        assertEquals("1234567890", context["user"]!!.jsonObject["onBehalfOfUser"]!!.jsonPrimitive.content)
    }

    @Test
    fun `des identifiants explicites remplacent ceux du stockage et ne declenchent pas la reconnexion`() = runTest {
        server.enqueue(error(401))
        val other = testCredentials.copy(cookie = "SAPISID=other", authUser = "2")

        appError { client.post("account/account_menu", credentials = other) }

        val request = server.takeRequest()
        assertEquals("2", request.headers["X-Goog-AuthUser"])
        assertEquals(0, sessions.reauthMarked)
    }

    @Test
    fun `HTTP 401 ou 403 demande la reconnexion et marque le compte`() = runTest {
        server.enqueue(error(403, """{"error":{"code":403,"message":"The caller does not have permission"}}"""))

        val e = appError { client.post("browse") }

        assertEquals(AppError.YouTubeAuthRequired, e.error)
        assertEquals(1, sessions.reauthMarked)
        assertTrue(sessions.stateFlow.value is AccountState.ReauthRequired)
    }

    @Test
    fun `HTTP 429 est du throttling, 5xx une erreur reseau`() = runTest {
        server.enqueue(error(429))
        server.enqueue(error(503))

        assertEquals(AppError.BotDetected, appError { client.post("browse") }.error)
        assertEquals(AppError.Network, appError { client.post("browse") }.error)
    }

    @Test
    fun `HTTP 400 donne une erreur de synchro avec endpoint et message sans fuiter le cookie`() = runTest {
        server.enqueue(error(400, """{"error":{"code":400,"message":"Request contains an invalid argument."}}"""))

        val e = appError { client.post("browse/edit_playlist") }

        val failed = e.error as AppError.YouTubeSyncFailed
        assertEquals("browse/edit_playlist", failed.endpoint)
        assertTrue(failed.detail!!.contains("HTTP 400"))
        assertTrue(failed.detail!!.contains("invalid argument"))
        assertTrue(!failed.detail!!.contains("SAPISID"))
    }

    @Test
    fun `une erreur annoncee dans un HTTP 200 est traitee comme une erreur`() = runTest {
        server.enqueue(ok("""{"error":{"code":401,"message":"Request had invalid authentication credentials."}}"""))

        val e = appError { client.post("browse") }

        assertEquals(AppError.YouTubeAuthRequired, e.error)
    }

    @Test
    fun `reponse non JSON ou non objet - erreur de synchro claire`() = runTest {
        server.enqueue(ok("<html>pas du json</html>"))
        server.enqueue(ok("[1,2]"))

        val html = appError { client.post("browse") }
        assertTrue((html.error as AppError.YouTubeSyncFailed).detail!!.contains("JSON"))
        val array = appError { client.post("browse") }
        assertTrue((array.error as AppError.YouTubeSyncFailed).detail!!.contains("objet"))
    }

    @Test
    fun `sans identifiants ou sans cookie SAPISID, aucune requete n est envoyee`() = runTest {
        sessions.storedCredentials = null
        assertEquals(AppError.YouTubeAuthRequired, appError { client.post("browse") }.error)

        sessions.storedCredentials = testCredentials.copy(cookie = "SID=abc")
        assertEquals(AppError.YouTubeAuthRequired, appError { client.post("browse") }.error)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `coupure reseau - erreur Network`() = runTest {
        server.close()
        val e = appError { client.post("browse") }
        assertEquals(AppError.Network, e.error)
    }

    @Test
    fun `la reponse est renvoyee telle quelle`() = runTest {
        server.enqueue(ok("""{"status":"STATUS_SUCCEEDED"}"""))
        val response: JsonObject = client.post("browse/edit_playlist")
        assertEquals("STATUS_SUCCEEDED", response["status"]!!.jsonPrimitive.content)
        assertNull(response["absent"])
    }
}
