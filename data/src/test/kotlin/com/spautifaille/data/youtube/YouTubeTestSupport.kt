package com.spautifaille.data.youtube

import com.spautifaille.data.youtube.api.AddedEntry
import com.spautifaille.data.youtube.api.RemoteChannel
import com.spautifaille.data.youtube.api.RemoteLibraryEntry
import com.spautifaille.data.youtube.api.RemotePlaylist
import com.spautifaille.data.youtube.api.RemoteTrack
import com.spautifaille.data.youtube.api.YouTubeRemote
import com.spautifaille.data.youtube.session.SyncMetaStore
import com.spautifaille.data.youtube.session.YouTubeSessionStore
import com.spautifaille.data.youtube.sync.SyncScheduler
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.SyncError
import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.domain.youtube.YouTubeCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Charge une fixture de `src/test/resources/youtube/` (voir le README de ce dossier pour la provenance). */
internal fun fixture(name: String): JsonElement {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/youtube/$name")) { "fixture introuvable : $name" }
    return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
}

internal val testAccount = YouTubeAccount("Jules Test", "jules.test@example.com", "@julestest", "https://yt3.ggpht.com/a")
internal val testCredentials = YouTubeCredentials(
    cookie = "SID=abc; SAPISID=AbCdEf123_sapisid-VALUE; __Secure-3PAPISID=AbCdEf123_sapisid-VALUE",
    visitorData = "VISITOR",
    dataSyncId = "1234567890||",
    authUser = "0",
)

internal fun remoteTrack(videoId: String, setVideoId: String? = null, title: String = "Titre $videoId") = RemoteTrack(
    videoId = videoId,
    setVideoId = setVideoId,
    title = title,
    artist = "Artiste",
    artistChannelId = "UCxEqaQWosMHaTih-tgzDqug",
    album = "Album",
    durationMs = 180_000,
    thumbnailUrl = "https://img/$videoId.jpg",
)

internal class FakeSessionStore(initial: AccountState = AccountState.SignedIn(testAccount)) : YouTubeSessionStore {
    val stateFlow = MutableStateFlow(initial)
    var storedCredentials: YouTubeCredentials? = testCredentials
    var reauthMarked = 0

    override val state: Flow<AccountState> = stateFlow
    override suspend fun credentials(): YouTubeCredentials? = storedCredentials
    override suspend fun isLinked(): Boolean = stateFlow.value !is AccountState.SignedOut

    override suspend fun save(credentials: YouTubeCredentials, account: YouTubeAccount) {
        storedCredentials = credentials
        stateFlow.value = AccountState.SignedIn(account)
    }

    override suspend fun markReauthRequired() {
        reauthMarked++
        (stateFlow.value as? AccountState.SignedIn)?.let { stateFlow.value = AccountState.ReauthRequired(it.account) }
    }

    override suspend fun clear() {
        storedCredentials = null
        stateFlow.value = AccountState.SignedOut
    }
}

internal class FakeMetaStore : SyncMetaStore {
    val last = MutableStateFlow<Long?>(null)
    val error = MutableStateFlow<SyncError?>(null)
    val musicOnly = MutableStateFlow(false)
    override val lastSyncAt: Flow<Long?> = last
    override val lastError: Flow<SyncError?> = error
    override val likesMusicOnly: Flow<Boolean> = musicOnly
    override suspend fun setLastSyncAt(at: Long) { last.value = at }
    override suspend fun setLastError(error: SyncError?) { this.error.value = error }
    override suspend fun setLikesMusicOnly(enabled: Boolean) { musicOnly.value = enabled }
    override suspend fun clear() {
        last.value = null
        error.value = null
    }
}

internal class FakeScheduler : SyncScheduler {
    var flushes = 0
    var fullSyncs = 0
    var replaced = 0
    var periodic = 0
    var cancelled = 0
    override fun scheduleFlush() { flushes++ }
    override fun requestFullSync(replace: Boolean) {
        fullSyncs++
        if (replace) replaced++
    }
    override fun schedulePeriodic() { periodic++ }
    override fun cancelAll() { cancelled++ }
}

/**
 * Compte YouTube simulé en mémoire : likes, abonnements, playlists (avec `setVideoId` générés comme YouTube), et
 * journal des appels. [failures] permet d'injecter une erreur sur un appel (clé = début de l'entrée du journal).
 */
internal class FakeYouTubeRemote : YouTubeRemote {
    class FakePlaylist(var title: String, var owned: Boolean, val items: MutableList<RemoteTrack> = mutableListOf())

    var account: YouTubeAccount? = testAccount
    /** « Musique likée » : du plus ancien au plus récent (renvoyée à l'envers, comme YouTube). */
    val likedMusic = LinkedHashSet<String>()
    /** Vidéos aimées non musicales : visibles uniquement dans « Vidéos J'aime ». */
    val likedOther = LinkedHashSet<String>()
    val nonMusic = mutableSetOf<String>()
    var likedAllSupported = true
    /** Chaînes abonnées mais absentes de la liste renvoyée (chaînes non musicales). */
    var hideFromListing: Set<String> = emptySet()
    val channels = LinkedHashMap<String, RemoteChannel>()
    val libraryEntries = mutableListOf<RemoteLibraryEntry>()
    val playlists = LinkedHashMap<String, FakePlaylist>()
    val calls = mutableListOf<String>()
    val failures = mutableListOf<Pair<String, AppError>>()
    private var nextSetVideoId = 1
    private var nextPlaylist = 1

    /** Appelé avant chaque appel simulé : permet de modifier la base locale pendant une synchro. */
    var hook: (suspend (String) -> Unit)? = null

    private suspend fun record(call: String) {
        calls += call
        hook?.invoke(call)
        failures.firstOrNull { call.startsWith(it.first) }?.let { throw AppException(it.second) }
    }

    fun callsStartingWith(prefix: String) = calls.filter { it.startsWith(prefix) }

    fun addPlaylist(id: String, title: String, owned: Boolean, vararg videoIds: String): FakePlaylist {
        val playlist = FakePlaylist(title, owned)
        videoIds.forEach { playlist.items += remoteTrack(it, "SV${nextSetVideoId++}") }
        playlists[id] = playlist
        return playlist
    }

    fun videoIds(playlistId: String): List<String> = playlists.getValue(playlistId).items.map { it.videoId }

    override suspend fun accountInfo(credentials: YouTubeCredentials?): YouTubeAccount? {
        record("accountInfo")
        return account
    }

    override suspend fun libraryPlaylists(): List<RemoteLibraryEntry> {
        record("libraryPlaylists")
        return libraryEntries.toList()
    }

    override suspend fun playlist(id: String): RemotePlaylist {
        record("playlist:$id")
        val playlist = playlists[id] ?: throw AppException(AppError.YouTubeSyncFailed("browse", "playlist inconnue $id"))
        return RemotePlaylist(id, playlist.title, playlist.owned, playlist.items.size, playlist.items.toList())
    }

    override suspend fun likedMusic(): List<RemoteTrack> {
        record("likedMusic")
        return likedMusic.toList().reversed().map { remoteTrack(it) }
    }

    override suspend fun likedAll(): List<RemoteTrack> {
        record("likedAll")
        if (!likedAllSupported) throw AppException(AppError.YouTubeSyncFailed("browse", "VLLL non servi"))
        return (likedMusic + likedOther).toList().reversed().map { remoteTrack(it) }
    }

    override suspend fun subscriptions(): List<RemoteChannel> {
        record("subscriptions")
        return channels.values.filter { it.channelId !in hideFromListing }
    }

    override suspend fun like(videoId: String) {
        record("like:$videoId")
        if (videoId in nonMusic) likedOther += videoId else likedMusic += videoId
    }

    override suspend fun unlike(videoId: String) {
        record("unlike:$videoId")
        likedMusic -= videoId
        likedOther -= videoId
    }

    override suspend fun subscribe(channelId: String) {
        record("subscribe:$channelId")
        channels.getOrPut(channelId) { RemoteChannel(channelId, "Chaîne $channelId", null) }
    }

    override suspend fun unsubscribe(channelId: String) {
        record("unsubscribe:$channelId")
        channels.remove(channelId)
    }

    override suspend fun addToPlaylist(playlistId: String, videoIds: List<String>, allowDuplicates: Boolean): List<AddedEntry> {
        record("add:$playlistId:${videoIds.joinToString(",")}")
        val playlist = playlists.getValue(playlistId)
        return videoIds.map { id ->
            val setVideoId = "SV${nextSetVideoId++}"
            playlist.items += remoteTrack(id, setVideoId)
            AddedEntry(id, setVideoId)
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) {
        record("remove:$playlistId:${entries.joinToString(",") { it.second }}")
        val playlist = playlists.getValue(playlistId)
        playlist.items.removeAll { item -> entries.any { it.first == item.setVideoId } }
    }

    override suspend fun moveInPlaylist(playlistId: String, setVideoId: String, beforeSetVideoId: String) {
        record("move:$playlistId:$setVideoId:$beforeSetVideoId")
        val items = playlists.getValue(playlistId).items
        val moved = items.first { it.setVideoId == setVideoId }
        items.remove(moved)
        items.add(items.indexOfFirst { it.setVideoId == beforeSetVideoId }, moved)
    }

    override suspend fun createPlaylist(title: String, videoIds: List<String>): String {
        record("create:$title:${videoIds.joinToString(",")}")
        val id = "PLnew${nextPlaylist++}"
        addPlaylist(id, title, true, *videoIds.toTypedArray())
        return id
    }
}
