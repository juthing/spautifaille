package com.spautifaille.data.youtube.api

import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.domain.youtube.YouTubeChannelIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Parsers tolérants des réponses InnerTube (client `WEB_REMIX`, YouTube Music). Les chemins JSON viennent de
 * ytmusicapi (`navigation.py`, `parsers/library.py`, `parsers/playlists.py`) et de Metrolist (`LibraryPage`,
 * `AccountMenuResponse`). Principe : un champ optionnel absent donne `null` ; un champ indispensable absent lève
 * [YouTubeParseException] avec l'endpoint et le chemin manquant (jamais de crash ni de résultat silencieusement vide).
 */
internal object InnerTubeParsers {

    /** Playlists système de la bibliothèque, non importables comme playlists liées. */
    private val systemPlaylistIds = setOf("LM", "LL", "SE", "WL", "RDPN")

    private const val GREY_OUT = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"

    // region Compte

    /**
     * `account/account_menu` → profil, ou `null` si la réponse ne contient pas d'en-tête de compte actif (cookies
     * refusés : YouTube renvoie alors le menu « Se connecter »).
     */
    fun parseAccountInfo(root: JsonElement): YouTubeAccount? {
        val header = root.at(
            "actions", 0, "openPopupAction", "popup", "multiPageMenuRenderer", "header", "activeAccountHeaderRenderer",
        ) ?: return null
        val name = header.at("accountName").firstRunText() ?: return null
        return YouTubeAccount(
            name = name,
            email = header.at("email").firstRunText(),
            handle = header.at("channelHandle").firstRunText(),
            avatarUrl = header.at("accountPhoto", "thumbnails").lastThumbnailUrl(),
        )
    }

    // endregion

    // region Playlists de la bibliothèque

    /** `browse FEmusic_liked_playlists` (première page) : grille de `musicTwoRowItemRenderer`. */
    fun parseLibraryPlaylists(root: JsonElement, endpoint: String = "browse"): Page<RemoteLibraryEntry> {
        val grid = root.at("contents").findFirst("gridRenderer")
            ?: return if (root.at("contents", "singleColumnBrowseResultsRenderer") != null) {
                Page(emptyList(), null)
            } else {
                throw YouTubeParseException(endpoint, "gridRenderer introuvable (contents.singleColumnBrowseResultsRenderer)")
            }
        return parseLibraryGrid(grid.at("items").asArray(), continuationOf(grid))
    }

    /** Page suivante : `continuationContents.gridContinuation`. */
    fun parseLibraryPlaylistsContinuation(root: JsonElement, endpoint: String = "browse"): Page<RemoteLibraryEntry> {
        val grid = root.at("continuationContents", "gridContinuation")
            ?: throw YouTubeParseException(endpoint, "continuationContents.gridContinuation introuvable")
        return parseLibraryGrid(grid.at("items").asArray(), continuationOf(grid))
    }

    private fun parseLibraryGrid(items: JsonArray?, continuation: String?): Page<RemoteLibraryEntry> {
        val entries = ArrayList<RemoteLibraryEntry>()
        var skipped = 0
        for (item in items.orEmpty()) {
            val renderer = item.at("musicTwoRowItemRenderer") ?: continue
            val entry = parseLibraryEntry(renderer)
            if (entry == null) {
                // « Nouvelle playlist », playlists système (VLLM, VLSE…) : sans identifiant de playlist exploitable.
                if (renderer.at("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").string() != null) skipped++
                continue
            }
            entries += entry
        }
        return Page(entries, continuation, skipped)
    }

    private fun parseLibraryEntry(renderer: JsonElement): RemoteLibraryEntry? {
        val browseId = renderer.at("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").string()
            ?: return null
        if (!browseId.startsWith("VL")) return null
        val id = browseId.removePrefix("VL")
        if (id in systemPlaylistIds) return null
        val title = renderer.at("title").firstRunText() ?: return null
        val owned = renderer.at("menu", "menuRenderer", "items").asArray().orEmpty().any {
            it.at("menuNavigationItemRenderer", "navigationEndpoint", "playlistEditorEndpoint", "playlistId").string() == id
        }
        val count = renderer.at("subtitle", "runs").asArray().orEmpty()
            .mapNotNull { it.at("text").string() }
            .lastOrNull()
            ?.let(::parseTrackCount)
        return RemoteLibraryEntry(
            id = id,
            title = title,
            thumbnailUrl = renderer.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails").lastThumbnailUrl(),
            trackCount = count,
            isOwned = owned,
        )
    }

    // endregion

    // region Abonnements

    /** `browse FEmusic_library_corpus_artists` : `musicShelfRenderer` de `musicResponsiveListItemRenderer`. */
    fun parseSubscriptions(root: JsonElement, endpoint: String = "browse"): Page<RemoteChannel> {
        val shelf = root.at("contents").findFirst("musicShelfRenderer")
            ?: return if (root.at("contents", "singleColumnBrowseResultsRenderer") != null) {
                Page(emptyList(), null)
            } else {
                throw YouTubeParseException(endpoint, "musicShelfRenderer introuvable (contents.singleColumnBrowseResultsRenderer)")
            }
        return parseChannels(shelf.at("contents").asArray(), continuationOf(shelf))
    }

    fun parseSubscriptionsContinuation(root: JsonElement, endpoint: String = "browse"): Page<RemoteChannel> {
        val shelf = root.at("continuationContents", "musicShelfContinuation")
            ?: throw YouTubeParseException(endpoint, "continuationContents.musicShelfContinuation introuvable")
        return parseChannels(shelf.at("contents").asArray(), continuationOf(shelf))
    }

    private fun parseChannels(items: JsonArray?, continuation: String?): Page<RemoteChannel> {
        val channels = ArrayList<RemoteChannel>()
        var skipped = 0
        for (item in items.orEmpty()) {
            val renderer = item.at("musicResponsiveListItemRenderer") ?: continue
            val channelId = renderer.at("navigationEndpoint", "browseEndpoint", "browseId").string()
            val name = renderer.at("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer", "text").firstRunText()
            if (channelId == null || name == null || !YouTubeChannelIds.isChannelId(channelId)) {
                skipped++
                continue
            }
            channels += RemoteChannel(
                channelId = channelId,
                name = name,
                avatarUrl = renderer.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").lastThumbnailUrl(),
            )
        }
        return Page(channels, continuation, skipped)
    }

    // endregion

    // region Playlists (et likes LM / LL)

    /** `browse VL<id>` (première page). */
    fun parsePlaylist(root: JsonElement, endpoint: String = "browse"): PlaylistPage {
        val tab = root.at(
            "contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer",
            "contents", 0,
        )
        val editable = tab.at("musicEditablePlaylistDetailHeaderRenderer")
        val header = editable.at("header", "musicResponsiveHeaderRenderer")
            ?: tab.at("musicResponsiveHeaderRenderer")
            ?: editable.at("header", "musicDetailHeaderRenderer")
            ?: root.at("header", "musicDetailHeaderRenderer")
        val shelf = root.at("contents", "twoColumnBrowseResultsRenderer", "secondaryContents").findFirst("musicPlaylistShelfRenderer")
            ?: root.at("contents", "twoColumnBrowseResultsRenderer", "secondaryContents").findFirst("musicShelfRenderer")
        if (header == null && shelf == null) {
            throw YouTubeParseException(endpoint, "en-tête et liste de titres introuvables (contents.twoColumnBrowseResultsRenderer)")
        }

        val title = editable.at("editHeader", "musicPlaylistEditHeaderRenderer", "title").text()
            ?: header.at("title").text()
        val playlistId = editable.at("playlistId").string() ?: shelf.at("playlistId").string()
        val trackCount = header.at("secondSubtitle").firstRunText()?.let(::parseTrackCount)
        val page = if (shelf == null) {
            Page(emptyList(), null)
        } else {
            parsePlaylistItems(shelf.at("contents").asArray(), continuationOf(shelf))
        }
        return PlaylistPage(playlistId, title, isOwned = editable != null, trackCount = trackCount, page = page)
    }

    /**
     * Page suivante d'une playlist : réponse moderne (`onResponseReceivedActions[0].appendContinuationItemsAction`)
     * ou historique (`continuationContents.musicPlaylistShelfContinuation`).
     */
    fun parsePlaylistContinuation(root: JsonElement, endpoint: String = "browse"): Page<RemoteTrack> {
        val appended = root.at("onResponseReceivedActions", 0, "appendContinuationItemsAction", "continuationItems").asArray()
        if (appended != null) return parsePlaylistItems(appended, lastContinuationToken(appended))
        val legacy = root.at("continuationContents", "musicPlaylistShelfContinuation")
            ?: root.at("continuationContents", "musicShelfContinuation")
            ?: throw YouTubeParseException(endpoint, "continuation de playlist introuvable (onResponseReceivedActions / continuationContents)")
        return parsePlaylistItems(legacy.at("contents").asArray(), continuationOf(legacy))
    }

    private fun parsePlaylistItems(items: JsonArray?, continuation: String?): Page<RemoteTrack> {
        val tracks = ArrayList<RemoteTrack>()
        var skipped = 0
        for (item in items.orEmpty()) {
            val renderer = item.at("musicResponsiveListItemRenderer") ?: continue
            val track = parsePlaylistItem(renderer)
            if (track == null) skipped++ else tracks += track
        }
        return Page(tracks, continuation, skipped)
    }

    /** `null` si ni `videoId` ni titre exploitable (titre supprimé…). */
    fun parsePlaylistItem(renderer: JsonElement): RemoteTrack? {
        val overlayWatch = renderer.at(
            "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
            "playNavigationEndpoint", "watchEndpoint",
        )
        var videoId = overlayWatch.at("videoId").string()
        var setVideoId = overlayWatch.at("playlistSetVideoId").string()
        for (menuItem in renderer.at("menu", "menuRenderer", "items").asArray().orEmpty()) {
            val edit = menuItem.at("menuServiceItemRenderer", "serviceEndpoint", "playlistEditEndpoint", "actions", 0) ?: continue
            setVideoId = setVideoId ?: edit.at("setVideoId").string()
            videoId = videoId ?: edit.at("removedVideoId").string()
        }

        val columns = renderer.at("flexColumns").asArray().orEmpty()
            .map { it.at("musicResponsiveListItemFlexColumnRenderer", "text") }
        var titleIndex: Int? = null
        var artistIndex: Int? = null
        var albumIndex: Int? = null
        var channelIndex: Int? = null
        var durationFromFlex: String? = null
        columns.forEachIndexed { index, text ->
            val endpoint = text.at("runs", 0, "navigationEndpoint")
            if (endpoint == null) {
                val run = text.firstRunText()
                if (run != null && durationPattern.matches(run)) durationFromFlex = run
                return@forEachIndexed
            }
            videoId = videoId ?: endpoint.at("watchEndpoint", "videoId").string()
            if (endpoint.at("watchEndpoint") != null) {
                titleIndex = index
                return@forEachIndexed
            }
            when (
                endpoint.at(
                    "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
                ).string()
            ) {
                "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_UNKNOWN" -> artistIndex = artistIndex ?: index
                "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK" -> albumIndex = albumIndex ?: index
                "MUSIC_PAGE_TYPE_USER_CHANNEL" -> channelIndex = channelIndex ?: index
                "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE" -> titleIndex = index
            }
        }
        val id = videoId ?: return null

        val title = columns.getOrNull(titleIndex ?: 0).text() ?: return null
        val effectiveArtistIndex = artistIndex ?: channelIndex ?: if (columns.size > 1 && albumIndex != 1) 1 else null
        val artistColumn = effectiveArtistIndex?.let(columns::getOrNull)
        val channelId = artistColumn.at("runs").asArray().orEmpty()
            .firstNotNullOfOrNull { it.at("navigationEndpoint", "browseEndpoint", "browseId").string() }
            ?.takeIf(YouTubeChannelIds::isChannelId)

        val fixed = renderer.at("fixedColumns", 0, "musicResponsiveListItemFixedColumnRenderer", "text")
        val duration = fixed.at("simpleText").string() ?: fixed.firstRunText() ?: durationFromFlex

        return RemoteTrack(
            videoId = id,
            setVideoId = setVideoId,
            title = title,
            artist = artistColumn.text(),
            artistChannelId = channelId,
            album = albumIndex?.let(columns::getOrNull).text(),
            durationMs = duration?.let(::parseDurationMs),
            thumbnailUrl = renderer.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").lastThumbnailUrl(),
            isAvailable = renderer.at("musicItemRendererDisplayPolicy").string() != GREY_OUT,
        )
    }

    // endregion

    // region Écritures

    /** Réponse de `browse/edit_playlist` : lève si `status` n'indique pas un succès. */
    fun checkEditStatus(root: JsonElement, endpoint: String = "browse/edit_playlist") {
        val status = root.at("status").string()
        if (status == null || !status.contains("SUCCEEDED")) {
            throw YouTubeParseException(endpoint, "statut inattendu : ${status ?: "absent"}")
        }
    }

    /** `playlistEditResults[].playlistEditVideoAddedResultData` de `browse/edit_playlist` après `ACTION_ADD_VIDEO`. */
    fun parseAddedEntries(root: JsonElement): List<AddedEntry> =
        root.at("playlistEditResults").asArray().orEmpty().mapNotNull { result ->
            val data = result.at("playlistEditVideoAddedResultData") ?: return@mapNotNull null
            val videoId = data.at("videoId").string() ?: return@mapNotNull null
            AddedEntry(videoId, data.at("setVideoId").string())
        }

    /** `playlist/create` → identifiant de la nouvelle playlist. */
    fun parseCreatedPlaylistId(root: JsonElement, endpoint: String = "playlist/create"): String =
        root.at("playlistId").string()
            ?: throw YouTubeParseException(endpoint, "playlistId absent de la réponse")

    // endregion

    // region Utilitaires

    private val durationPattern = Regex("""^(\d+:)*\d+:\d{2}$""")
    private val countPattern = Regex("""^(\d[\d\s.,  ]*)\s*\S+""")

    /** « 245 tracks » → 245 ; « 1,234 songs » → 1234 ; texte sans nombre → `null`. */
    fun parseTrackCount(text: String): Int? {
        val digits = countPattern.find(text.trim())?.groupValues?.get(1)?.filter(Char::isDigit)
        return digits?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    }

    /** « 3:38 » / « 1:02:03 » → millisecondes ; `null` si le format n'est pas reconnu. */
    fun parseDurationMs(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toLong() }
        val seconds = numbers.fold(0L) { acc, n -> acc * 60 + n }
        return seconds * 1000
    }

    /** Jeton de continuation d'un conteneur : forme historique (`continuations`) ou dernier `continuationItemRenderer`. */
    private fun continuationOf(container: JsonElement?): String? =
        container.at("continuations", 0, "nextContinuationData", "continuation").string()
            ?: container.at("continuations", 0, "reloadContinuationData", "continuation").string()
            ?: lastContinuationToken(container.at("contents").asArray() ?: container.at("items").asArray())

    private fun lastContinuationToken(items: JsonArray?): String? {
        val last = items?.lastOrNull() ?: return null
        val renderer = last.at("continuationItemRenderer") ?: return null
        renderer.at("continuationEndpoint", "continuationCommand", "token").string()?.let { return it }
        return renderer.at("continuationEndpoint", "commandExecutorCommand", "commands").asArray().orEmpty()
            .firstNotNullOfOrNull { it.at("continuationCommand", "token").string() }
    }

    // endregion
}
