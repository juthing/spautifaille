package com.spautifaille.data.youtube.api

import com.spautifaille.data.youtube.fixture
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les deux premiers groupes lisent des réponses **réelles** (ytmusicapi, voir `src/test/resources/youtube/README.md`) ;
 * les autres, des fichiers synthétiques dont la structure suit le code des parsers de référence.
 */
class InnerTubeParsersTest {

    // region Réponses réelles (ytmusicapi)

    @Test
    fun `playlist possedee reelle - en-tete et titres`() {
        val page = InnerTubeParsers.parsePlaylist(fixture("ytmusicapi_get_playlist_owned_trimmed.json"))

        assertEquals("PLaZPMsuQNCsWn0iVMtGbaUXO6z-EdZaZm", page.playlistId)
        assertTrue(page.isOwned)
        assertEquals("03 Jan 12:09", page.title)
        assertEquals(245, page.trackCount)
        assertEquals(4, page.page.items.size)
        assertEquals(0, page.page.skipped)

        val first = page.page.items.first()
        assertEquals("3_R4ulvg8OY", first.videoId)
        assertEquals("56B44F6D10557CC6", first.setVideoId)
        assertEquals("I Hate Everything (feat. Action Bronson)", first.title)
        assertEquals("The Alchemist", first.artist)
        assertEquals("UC2Eotb0QaPkaJI4Cw4oHZ6Q", first.artistChannelId)
        assertEquals("The Food Villain", first.album)
        assertEquals(83_000L, first.durationMs)
        assertTrue(first.thumbnailUrl!!.startsWith("https://lh3.googleusercontent.com/"))
        assertTrue(first.isAvailable)

        // Artiste « Paul McCartney & Linda McCartney » : texte complet de la colonne, première chaîne cliquable.
        val medley = page.page.items.last()
        assertEquals("Uncle Albert / Admiral Halsey (Medley)", medley.title)
        assertEquals("Paul McCartney & Linda McCartney", medley.artist)
        assertEquals(296_000L, medley.durationMs)
    }

    @Test
    fun `playlist possedee reelle - tous les titres ont un setVideoId distinct`() {
        val items = InnerTubeParsers.parsePlaylist(fixture("ytmusicapi_get_playlist_owned_trimmed.json")).page.items
        assertTrue(items.all { it.setVideoId != null })
        assertEquals(items.size, items.map { it.setVideoId }.toSet().size)
    }

    @Test
    fun `playlist possedee reelle - jeton de continuation historique`() {
        val page = InnerTubeParsers.parsePlaylist(fixture("ytmusicapi_get_playlist_owned_trimmed.json")).page
        assertNotNull(page.continuation)
        assertTrue(page.continuation!!.startsWith("4qmFsgL5ARIk"))
    }

    @Test
    fun `playlist publique reelle - non possedee, nombre de titres`() {
        val page = InnerTubeParsers.parsePlaylist(fixture("ytmusicapi_get_playlist_public_trimmed.json"))

        assertFalse(page.isOwned)
        assertEquals("Feel-Good Classic Rock", page.title)
        assertEquals(101, page.trackCount)
        assertEquals(4, page.page.items.size)
        assertEquals("7bX76VR6oQE", page.page.items.first().videoId)
    }

    // endregion

    // region Compte

    @Test
    fun `menu de compte - nom, e-mail, identifiant et photo`() {
        val account = InnerTubeParsers.parseAccountInfo(fixture("synthetic/account_menu.json"))!!
        assertEquals("Jules Test", account.name)
        assertEquals("jules.test@example.com", account.email)
        assertEquals("@julestest", account.handle)
        assertTrue(account.avatarUrl!!.contains("s88"))
    }

    @Test
    fun `menu de compte deconnecte - null`() {
        assertNull(InnerTubeParsers.parseAccountInfo(fixture("synthetic/account_menu_signed_out.json")))
        assertNull(InnerTubeParsers.parseAccountInfo(Json.parseToJsonElement("{}")))
    }

    // endregion

    // region Bibliothèque

    @Test
    fun `playlists de la bibliotheque - ignore la creation et les playlists systeme`() {
        val page = InnerTubeParsers.parseLibraryPlaylists(fixture("synthetic/library_playlists_grid.json"))

        assertEquals(listOf("PLownedPLAYLISTid0000000000000000001", "PLsavedPLAYLISTid00000000000000000002"), page.items.map { it.id })
        val owned = page.items[0]
        assertEquals("Road trip", owned.title)
        assertTrue(owned.isOwned)
        assertEquals(1234, owned.trackCount)
        assertTrue(owned.thumbnailUrl!!.contains("w226"))
        val saved = page.items[1]
        assertFalse(saved.isOwned)
        assertEquals(50, saved.trackCount)
        assertEquals("CONT_GRID_2", page.continuation)
        // « Liked Music » (VLLM) est ignorée mais comptée.
        assertEquals(1, page.skipped)
    }

    @Test
    fun `playlists de la bibliotheque - page de continuation`() {
        val page = InnerTubeParsers.parseLibraryPlaylistsContinuation(fixture("synthetic/library_playlists_continuation.json"))
        assertEquals(listOf("Soirée"), page.items.map { it.title })
        assertTrue(page.items.single().isOwned)
        assertNull(page.continuation)
    }

    @Test
    fun `playlists de la bibliotheque - structure inattendue donne une erreur claire`() {
        val error = assertThrows(YouTubeParseException::class.java) {
            InnerTubeParsers.parseLibraryPlaylists(Json.parseToJsonElement("""{"contents":{"autreChose":{}}}"""), "browse")
        }
        assertEquals("browse", error.endpoint)
        assertTrue(error.detail.contains("gridRenderer"))
    }

    @Test
    fun `bibliotheque vide - liste vide sans erreur`() {
        val empty = Json.parseToJsonElement("""{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[]}}}""")
        assertTrue(InnerTubeParsers.parseLibraryPlaylists(empty).items.isEmpty())
        assertTrue(InnerTubeParsers.parseSubscriptions(empty).items.isEmpty())
    }

    @Test
    fun `abonnements - identifiants de chaine, noms et continuation`() {
        val page = InnerTubeParsers.parseSubscriptions(fixture("synthetic/library_subscriptions.json"))

        assertEquals(listOf("UCxEqaQWosMHaTih-tgzDqug", "UC2Eotb0QaPkaJI4Cw4oHZ6Q"), page.items.map { it.channelId })
        assertEquals(listOf("Artiste A", "Artiste B"), page.items.map { it.name })
        assertTrue(page.items[0].avatarUrl!!.contains("w226"))
        assertNull(page.items[1].avatarUrl)
        assertEquals(1, page.skipped)
        assertEquals("CONT_SUBS_2", page.continuation)

        val next = InnerTubeParsers.parseSubscriptionsContinuation(fixture("synthetic/library_subscriptions_continuation.json"))
        assertEquals(listOf("UCUX1nzkfAaWhXMYSAlMMbmw"), next.items.map { it.channelId })
        assertNull(next.continuation)
    }

    // endregion

    // region Continuations de playlist

    @Test
    fun `continuation de playlist moderne - titres et jeton suivant`() {
        val page = InnerTubeParsers.parsePlaylistContinuation(fixture("synthetic/playlist_continuation_modern.json"))
        val track = page.items.single()
        assertEquals("vidModern001", track.videoId)
        assertEquals("SETMODERN001", track.setVideoId)
        assertEquals("Titre moderne", track.title)
        assertEquals("Artiste M", track.artist)
        assertEquals(185_000L, track.durationMs)
        assertEquals("TOKEN_MODERN_NEXT", page.continuation)
    }

    @Test
    fun `continuation de playlist historique`() {
        val page = InnerTubeParsers.parsePlaylistContinuation(fixture("synthetic/playlist_continuation_legacy.json"))
        assertEquals("vidLegacy0001", page.items.single().videoId)
        assertEquals("SETLEGACY001", page.items.single().setVideoId)
        assertNull(page.continuation)
    }

    @Test
    fun `continuation de playlist inconnue donne une erreur claire`() {
        val error = assertThrows(YouTubeParseException::class.java) {
            InnerTubeParsers.parsePlaylistContinuation(Json.parseToJsonElement("{}"))
        }
        assertTrue(error.detail.contains("continuation"))
    }

    // endregion

    // region Tolérance

    @Test
    fun `un titre sans videoId est ignore et compte comme ignore`() {
        val broken = Json.parseToJsonElement(
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
                {"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Supprimé"}]}}}]}}
            ]}}]}""",
        )
        val page = InnerTubeParsers.parsePlaylistContinuation(broken)
        assertTrue(page.items.isEmpty())
        assertEquals(1, page.skipped)
    }

    @Test
    fun `un titre grise reste dans la liste, marque indisponible`() {
        val json = Json.parseToJsonElement(
            """{"musicItemRendererDisplayPolicy":"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT",
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Indisponible"}]}}}],
                "menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{"playlistEditEndpoint":{"actions":[{"setVideoId":"SV9","action":"ACTION_REMOVE_VIDEO","removedVideoId":"gone0000001"}]}}}}]}}}""",
        )
        val track = InnerTubeParsers.parsePlaylistItem(json)!!
        assertEquals("gone0000001", track.videoId)
        assertEquals("SV9", track.setVideoId)
        assertFalse(track.isAvailable)
    }

    @Test
    fun `statut d edition et ajouts`() {
        val ok = fixture("synthetic/edit_playlist_response.json")
        InnerTubeParsers.checkEditStatus(ok)
        assertEquals(
            listOf(AddedEntry("vidAdded0001", "SETADDED001"), AddedEntry("vidAdded0002", "SETADDED002")),
            InnerTubeParsers.parseAddedEntries(ok),
        )
        assertThrows(YouTubeParseException::class.java) {
            InnerTubeParsers.checkEditStatus(Json.parseToJsonElement("""{"status":"STATUS_FAILED"}"""))
        }
        assertEquals("PLabc", InnerTubeParsers.parseCreatedPlaylistId(Json.parseToJsonElement("""{"playlistId":"PLabc"}""")))
        assertThrows(YouTubeParseException::class.java) { InnerTubeParsers.parseCreatedPlaylistId(Json.parseToJsonElement("{}")) }
    }

    @Test
    fun `nombre de titres et durees dans plusieurs formats`() {
        assertEquals(245, InnerTubeParsers.parseTrackCount("245 tracks"))
        assertEquals(1234, InnerTubeParsers.parseTrackCount("1,234 songs"))
        assertEquals(1234, InnerTubeParsers.parseTrackCount("1 234 titres"))
        assertNull(InnerTubeParsers.parseTrackCount("Auto playlist"))
        assertEquals(83_000L, InnerTubeParsers.parseDurationMs("1:23"))
        assertEquals(3_723_000L, InnerTubeParsers.parseDurationMs("1:02:03"))
        assertNull(InnerTubeParsers.parseDurationMs("abc"))
        assertNull(InnerTubeParsers.parseDurationMs("12"))
    }

    // endregion
}
