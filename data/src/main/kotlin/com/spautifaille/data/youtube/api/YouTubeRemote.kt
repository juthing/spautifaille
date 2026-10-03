package com.spautifaille.data.youtube.api

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.domain.youtube.YouTubeCredentials
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opérations distantes du compte YouTube, abstraites pour que le moteur de synchronisation soit testable sans
 * réseau. Toutes lèvent `AppException(AppError)` (voir [InnerTubeClient] pour le mapping).
 */
internal interface YouTubeRemote {
    /** Profil du compte, ou `null` si YouTube ne reconnaît pas les identifiants. */
    suspend fun accountInfo(credentials: YouTubeCredentials? = null): YouTubeAccount?

    suspend fun libraryPlaylists(): List<RemoteLibraryEntry>
    suspend fun playlist(id: String): RemotePlaylist

    /** « Musique likée » (playlist `LM`), du plus récent au plus ancien. */
    suspend fun likedMusic(): List<RemoteTrack>

    /** « Vidéos J'aime » (playlist `LL`) ; peut lever `YouTubeSyncFailed` si l'endpoint ne la sert pas. */
    suspend fun likedAll(): List<RemoteTrack>

    suspend fun subscriptions(): List<RemoteChannel>

    suspend fun like(videoId: String)
    suspend fun unlike(videoId: String)
    suspend fun subscribe(channelId: String)
    suspend fun unsubscribe(channelId: String)

    /** Ajoute en fin de playlist. [allowDuplicates] : autorise un titre déjà présent. */
    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>, allowDuplicates: Boolean): List<AddedEntry>

    /** Retire des entrées ; chaque paire = (`setVideoId`, `videoId`). */
    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>)

    /** Place l'entrée [setVideoId] juste avant l'entrée [beforeSetVideoId] (`ACTION_MOVE_VIDEO_BEFORE`). */
    suspend fun moveInPlaylist(playlistId: String, setVideoId: String, beforeSetVideoId: String)

    suspend fun createPlaylist(title: String, videoIds: List<String>): String
}

@Singleton
internal class InnerTubeYouTubeRemote @Inject constructor(
    private val client: InnerTubeClient,
) : YouTubeRemote {

    override suspend fun accountInfo(credentials: YouTubeCredentials?): YouTubeAccount? {
        val response = client.post("account/account_menu", credentials = credentials)
        return InnerTubeParsers.parseAccountInfo(response)
    }

    override suspend fun libraryPlaylists(): List<RemoteLibraryEntry> {
        val body = browseBody("FEmusic_liked_playlists")
        val first = parsing("browse") { InnerTubeParsers.parseLibraryPlaylists(client.post("browse", body)) }
        return paginate("browse", first) { token ->
            parsing("browse") { InnerTubeParsers.parseLibraryPlaylistsContinuation(client.post("browse", continuationBody(token))) }
        }
    }

    override suspend fun playlist(id: String): RemotePlaylist {
        val first = parsing("browse") {
            InnerTubeParsers.parsePlaylist(client.post("browse", browseBody("VL${id.removePrefix("VL")}")))
        }
        if (first.page.items.isEmpty() && first.page.skipped > 0) {
            throw AppException(AppError.YouTubeSyncFailed("browse", "${first.page.skipped} entrées illisibles dans la playlist $id"))
        }
        val items = paginate("browse", first.page) { token ->
            parsing("browse") { InnerTubeParsers.parsePlaylistContinuation(client.post("browse", continuationBody(token))) }
        }
        return RemotePlaylist(
            id = id.removePrefix("VL"),
            title = first.title,
            isOwned = first.isOwned,
            trackCount = first.trackCount,
            items = items,
        )
    }

    override suspend fun likedMusic(): List<RemoteTrack> = playlist("LM").items

    override suspend fun likedAll(): List<RemoteTrack> = playlist("LL").items

    override suspend fun subscriptions(): List<RemoteChannel> {
        val first = parsing("browse") {
            InnerTubeParsers.parseSubscriptions(client.post("browse", browseBody("FEmusic_library_corpus_artists")))
        }
        return paginate("browse", first) { token ->
            parsing("browse") { InnerTubeParsers.parseSubscriptionsContinuation(client.post("browse", continuationBody(token))) }
        }
    }

    override suspend fun like(videoId: String) {
        client.post("like/like", videoTarget(videoId))
    }

    override suspend fun unlike(videoId: String) {
        client.post("like/removelike", videoTarget(videoId))
    }

    override suspend fun subscribe(channelId: String) {
        client.post("subscription/subscribe", subscriptionBody(channelId))
    }

    override suspend fun unsubscribe(channelId: String) {
        client.post("subscription/unsubscribe", subscriptionBody(channelId))
    }

    override suspend fun addToPlaylist(
        playlistId: String,
        videoIds: List<String>,
        allowDuplicates: Boolean,
    ): List<AddedEntry> {
        val added = ArrayList<AddedEntry>()
        for (chunk in videoIds.chunked(EDIT_CHUNK)) {
            val response = edit(playlistId, chunk.map { id ->
                buildJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", id)
                    if (allowDuplicates) put("dedupeOption", "DEDUPE_OPTION_SKIP")
                }
            })
            added += InnerTubeParsers.parseAddedEntries(response)
        }
        return added
    }

    override suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) {
        for (chunk in entries.chunked(EDIT_CHUNK)) {
            edit(playlistId, chunk.map { (setVideoId, videoId) ->
                buildJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                    put("removedVideoId", videoId)
                }
            })
        }
    }

    override suspend fun moveInPlaylist(playlistId: String, setVideoId: String, beforeSetVideoId: String) {
        edit(playlistId, listOf(buildJsonObject {
            put("action", "ACTION_MOVE_VIDEO_BEFORE")
            put("setVideoId", setVideoId)
            put("movedSetVideoIdSuccessor", beforeSetVideoId)
        }))
    }

    override suspend fun createPlaylist(title: String, videoIds: List<String>): String {
        // YouTube Music plante sur « < » et « > » dans le titre (ytmusicapi, create_playlist).
        val body = buildJsonObject {
            put("title", title.replace('<', ' ').replace('>', ' ').trim().ifEmpty { "Playlist" })
            put("description", "")
            put("privacyStatus", "PRIVATE")
            if (videoIds.isNotEmpty()) putJsonArray("videoIds") { videoIds.forEach { add(JsonPrimitive(it)) } }
        }
        val response = client.post("playlist/create", body)
        return parsing("playlist/create") { InnerTubeParsers.parseCreatedPlaylistId(response) }
    }

    // region Utilitaires

    private suspend fun edit(playlistId: String, actions: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            put("actions", JsonArray(actions))
        }
        val response = client.post("browse/edit_playlist", body)
        parsing("browse/edit_playlist") { InnerTubeParsers.checkEditStatus(response) }
        return response
    }

    private fun browseBody(browseId: String): JsonObject = buildJsonObject { put("browseId", browseId) }

    private fun continuationBody(token: String): JsonObject = buildJsonObject { put("continuation", token) }

    private fun videoTarget(videoId: String): JsonObject = buildJsonObject {
        putJsonObject("target") { put("videoId", videoId) }
    }

    private fun subscriptionBody(channelId: String): JsonObject = buildJsonObject {
        putJsonArray("channelIds") { add(JsonPrimitive(channelId)) }
        // Paramètre requis par YouTube Music pour s'abonner (valeur par défaut de Metrolist / InnerTubeX).
        put("params", SUBSCRIBE_PARAMS)
    }

    /** Suit les jetons de continuation avec garde-fous : jeton répété, page vide, trop de pages. */
    private suspend fun <T> paginate(endpoint: String, first: Page<T>, next: suspend (String) -> Page<T>): List<T> {
        val all = ArrayList(first.items)
        var token = first.continuation
        val seen = HashSet<String>()
        var pages = 0
        while (token != null) {
            if (!seen.add(token)) {
                throw AppException(AppError.YouTubeSyncFailed(endpoint, "pagination : jeton de continuation répété"))
            }
            if (++pages > MAX_PAGES) {
                throw AppException(AppError.YouTubeSyncFailed(endpoint, "pagination : plus de $MAX_PAGES pages"))
            }
            val page = next(token)
            if (page.items.isEmpty()) break
            all += page.items
            token = page.continuation
        }
        return all
    }

    private inline fun <T> parsing(endpoint: String, block: () -> T): T = try {
        block()
    } catch (e: YouTubeParseException) {
        throw AppException(AppError.YouTubeSyncFailed(e.endpoint.ifEmpty { endpoint }, e.detail), e)
    }

    // endregion

    private companion object {
        const val EDIT_CHUNK = 50
        const val MAX_PAGES = 400
        const val SUBSCRIBE_PARAMS = "EgIIAhgA"
    }
}
