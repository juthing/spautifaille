package com.spautifaille.data.youtube.api

import com.spautifaille.data.youtube.FakeSessionStore
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Remote réel (client + parsers) contre un faux serveur InnerTube : chemins, corps de requête, pagination. */
class YouTubeRemoteTest {

    private lateinit var server: MockWebServer
    private lateinit var remote: InnerTubeYouTubeRemote

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val client = InnerTubeClient(
            http = OkHttpClient(),
            sessions = FakeSessionStore(),
            io = Dispatchers.Default,
            clock = { 1_700_000_000_000L },
            baseUrl = server.url("/youtubei/v1/").toString(),
        )
        remote = InnerTubeYouTubeRemote(client)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/youtube/$name")).bufferedReader().use { it.readText() }

    private fun enqueueResource(name: String) = server.enqueue(json(resource(name)))

    private fun json(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun RecordedRequest.path() = url.encodedPath.removePrefix("/youtubei/v1/")

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun JsonObject.actions(): JsonArray = this["actions"]!!.jsonArray

    @Test
    fun `profil du compte - null si les identifiants sont refuses`() = runTest {
        enqueueResource("synthetic/account_menu.json")
        enqueueResource("synthetic/account_menu_signed_out.json")

        assertEquals("Jules Test", remote.accountInfo()!!.name)
        assertNull(remote.accountInfo())
        assertEquals("account/account_menu", server.takeRequest().path())
    }

    @Test
    fun `playlists de la bibliotheque - suit la continuation`() = runTest {
        enqueueResource("synthetic/library_playlists_grid.json")
        enqueueResource("synthetic/library_playlists_continuation.json")

        val entries = remote.libraryPlaylists()

        assertEquals(listOf("Road trip", "Chill Hits", "Soirée"), entries.map { it.title })
        val first = server.takeRequest()
        assertEquals("browse", first.path())
        assertEquals("FEmusic_liked_playlists", first.json()["browseId"]!!.jsonPrimitive.content)
        assertEquals("CONT_GRID_2", server.takeRequest().json()["continuation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `abonnements - suit la continuation`() = runTest {
        enqueueResource("synthetic/library_subscriptions.json")
        enqueueResource("synthetic/library_subscriptions_continuation.json")

        val channels = remote.subscriptions()

        assertEquals(3, channels.size)
        assertEquals("FEmusic_library_corpus_artists", server.takeRequest().json()["browseId"]!!.jsonPrimitive.content)
        assertEquals("CONT_SUBS_2", server.takeRequest().json()["continuation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `playlist - reponse reelle puis pages de continuation`() = runTest {
        enqueueResource("ytmusicapi_get_playlist_owned_trimmed.json")
        enqueueResource("synthetic/playlist_continuation_modern.json")
        enqueueResource("synthetic/playlist_continuation_legacy.json")

        val playlist = remote.playlist("VLPLaZPMsuQNCsWn0iVMtGbaUXO6z-EdZaZm")

        assertEquals("PLaZPMsuQNCsWn0iVMtGbaUXO6z-EdZaZm", playlist.id)
        assertTrue(playlist.isOwned)
        assertEquals(6, playlist.items.size)
        assertEquals("vidLegacy0001", playlist.items.last().videoId)
        assertEquals("VLPLaZPMsuQNCsWn0iVMtGbaUXO6z-EdZaZm", server.takeRequest().json()["browseId"]!!.jsonPrimitive.content)
        assertTrue(server.takeRequest().json()["continuation"]!!.jsonPrimitive.content.startsWith("4qmFsgL5ARIk"))
        assertEquals("TOKEN_MODERN_NEXT", server.takeRequest().json()["continuation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `musique likee et videos aimees utilisent les playlists LM et LL`() = runTest {
        repeat(2) {
            enqueueResource("ytmusicapi_get_playlist_owned_trimmed.json")
            enqueueResource("synthetic/playlist_continuation_legacy.json")
        }

        remote.likedMusic()
        remote.likedAll()

        assertEquals("VLLM", server.takeRequest().json()["browseId"]!!.jsonPrimitive.content)
        server.takeRequest()
        assertEquals("VLLL", server.takeRequest().json()["browseId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pagination - un jeton de continuation repete est une erreur claire`() = runTest {
        enqueueResource("ytmusicapi_get_playlist_owned_trimmed.json")
        val loop = resource("synthetic/playlist_continuation_modern.json").replace("TOKEN_MODERN_NEXT", "4qmFsgL5ARIk-LOOP")
        server.enqueue(json(loop))
        server.enqueue(json(loop.replace("vidModern001", "vidModern002")))
        // La page 1 réelle renvoie un jeton historique ; la page modern renvoie 4qm…-LOOP deux fois de suite.
        val error = runCatching { remote.playlist("PLx") }.exceptionOrNull() as AppException
        assertTrue(error.error is AppError.YouTubeSyncFailed)
    }

    @Test
    fun `like, unlike, abonnement et desabonnement - chemins et corps`() = runTest {
        repeat(4) { server.enqueue(json("{}")) }

        remote.like("vid1")
        remote.unlike("vid2")
        remote.subscribe("UCxEqaQWosMHaTih-tgzDqug")
        remote.unsubscribe("UCxEqaQWosMHaTih-tgzDqug")

        val like = server.takeRequest()
        assertEquals("like/like", like.path())
        assertEquals("vid1", like.json()["target"]!!.jsonObject["videoId"]!!.jsonPrimitive.content)
        val unlike = server.takeRequest()
        assertEquals("like/removelike", unlike.path())
        assertEquals("vid2", unlike.json()["target"]!!.jsonObject["videoId"]!!.jsonPrimitive.content)
        val subscribe = server.takeRequest()
        assertEquals("subscription/subscribe", subscribe.path())
        assertEquals("UCxEqaQWosMHaTih-tgzDqug", subscribe.json()["channelIds"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("subscription/unsubscribe", server.takeRequest().path())
    }

    @Test
    fun `edition de playlist - ajout, retrait et deplacement`() = runTest {
        server.enqueue(json(resource("synthetic/edit_playlist_response.json")))
        server.enqueue(json("""{"status":"STATUS_SUCCEEDED"}"""))
        server.enqueue(json("""{"status":"STATUS_SUCCEEDED"}"""))

        val added = remote.addToPlaylist("VLPLx", listOf("vidAdded0001", "vidAdded0002"), allowDuplicates = true)
        remote.removeFromPlaylist("PLx", listOf("SV1" to "vidA"))
        remote.moveInPlaylist("PLx", "SV2", "SV1")

        assertEquals(listOf("SETADDED001", "SETADDED002"), added.map { it.setVideoId })
        val add = server.takeRequest()
        assertEquals("browse/edit_playlist", add.path())
        assertEquals("PLx", add.json()["playlistId"]!!.jsonPrimitive.content)
        val addAction = add.json().actions().first().jsonObject
        assertEquals("ACTION_ADD_VIDEO", addAction["action"]!!.jsonPrimitive.content)
        assertEquals("vidAdded0001", addAction["addedVideoId"]!!.jsonPrimitive.content)
        assertEquals("DEDUPE_OPTION_SKIP", addAction["dedupeOption"]!!.jsonPrimitive.content)
        val removeAction = server.takeRequest().json().actions().single().jsonObject
        assertEquals("ACTION_REMOVE_VIDEO", removeAction["action"]!!.jsonPrimitive.content)
        assertEquals("SV1", removeAction["setVideoId"]!!.jsonPrimitive.content)
        assertEquals("vidA", removeAction["removedVideoId"]!!.jsonPrimitive.content)
        val moveAction = server.takeRequest().json().actions().single().jsonObject
        assertEquals("ACTION_MOVE_VIDEO_BEFORE", moveAction["action"]!!.jsonPrimitive.content)
        assertEquals("SV2", moveAction["setVideoId"]!!.jsonPrimitive.content)
        assertEquals("SV1", moveAction["movedSetVideoIdSuccessor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `edition de playlist - un statut d echec est une erreur de synchro`() = runTest {
        server.enqueue(json("""{"status":"STATUS_FAILED"}"""))

        val error = runCatching { remote.removeFromPlaylist("PLx", listOf("SV1" to "vidA")) }.exceptionOrNull() as AppException

        val failed = error.error as AppError.YouTubeSyncFailed
        assertEquals("browse/edit_playlist", failed.endpoint)
        assertTrue(failed.detail!!.contains("STATUS_FAILED"))
    }

    @Test
    fun `creation de playlist - titre assaini, identifiant renvoye`() = runTest {
        server.enqueue(json("""{"playlistId":"PLnew"}"""))

        val id = remote.createPlaylist("Mix <1>", listOf("a", "b"))

        assertEquals("PLnew", id)
        val request = server.takeRequest()
        assertEquals("playlist/create", request.path())
        assertEquals("Mix  1", request.json()["title"]!!.jsonPrimitive.content)
        assertEquals("PRIVATE", request.json()["privacyStatus"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b"), request.json()["videoIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `playlist - structure inattendue donne une erreur avec l endpoint`() = runTest {
        server.enqueue(json("""{"contents":{}}"""))

        val error = runCatching { remote.playlist("PLx") }.exceptionOrNull() as AppException

        val failed = error.error as AppError.YouTubeSyncFailed
        assertEquals("browse", failed.endpoint)
        assertTrue(failed.detail!!.contains("twoColumnBrowseResultsRenderer"))
    }
}
