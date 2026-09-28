package com.spautifaille.ui.library

import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.PlaylistWithTracks
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Remplace `Dispatchers.Main` par un dispatcher de test non confiné (les `viewModelScope.launch` s'exécutent immédiatement). */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** Collecte [flow] en arrière-plan pour activer les `stateIn(WhileSubscribed)` pendant le test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.collectInBackground(flow: Flow<T>): Job =
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }

fun track(index: Int, durationMs: Long? = 60_000L) = Track(
    id = "video$index",
    title = "Titre $index",
    artist = "Artiste $index",
    artistUrl = "https://youtube.com/@artist$index",
    durationMs = durationMs,
)

/** Implémentation en mémoire de [PlaylistRepository], fidèle à la sémantique du contrat (positions contiguës). */
class FakePlaylistRepository : PlaylistRepository {
    private val playlists = MutableStateFlow(
        listOf(Playlist(Playlist.LIKED_ID, "Titres likés", 0, null, true, 0, 0)),
    )
    private val entries = MutableStateFlow<Map<Long, List<PlaylistEntry>>>(emptyMap())
    private var nextPlaylistId = 2L
    private var nextEntryId = 1L

    val createdNames = mutableListOf<String>()
    val moves = mutableListOf<Triple<Long, Int, Int>>()
    var failMoves = false

    fun seed(playlist: Playlist, tracks: List<Track> = emptyList()) {
        playlists.value = playlists.value.filterNot { it.id == playlist.id } + playlist.copy(trackCount = tracks.size)
        entries.value += playlist.id to tracks.mapIndexed { index, t -> PlaylistEntry(nextEntryId++, index, t) }
    }

    fun tracksOf(id: Long): List<Track> = entries.value[id].orEmpty().map { it.track }

    fun entriesOf(id: Long): List<PlaylistEntry> = entries.value[id].orEmpty()

    fun playlist(id: Long): Playlist? = playlists.value.firstOrNull { it.id == id }

    override fun observePlaylists(): Flow<List<Playlist>> = playlists

    override fun observePlaylist(id: Long): Flow<PlaylistWithTracks?> =
        combine(playlists, entries) { all, map ->
            all.firstOrNull { it.id == id }?.let { PlaylistWithTracks(it, map[id].orEmpty()) }
        }

    override suspend fun create(name: String, tracks: List<Track>): Long {
        createdNames += name
        val id = nextPlaylistId++
        seed(Playlist(id, name, tracks.size, null, false, 0, 0), tracks)
        return id
    }

    override suspend fun rename(id: Long, name: String) {
        playlists.value = playlists.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun delete(id: Long) {
        if (playlist(id)?.isSystem == true) return
        playlists.value = playlists.value.filterNot { it.id == id }
        entries.value -= id
    }

    override suspend fun addTracks(playlistId: Long, tracks: List<Track>) {
        val current = entries.value[playlistId].orEmpty()
        val added = tracks.mapIndexed { i, t -> PlaylistEntry(nextEntryId++, current.size + i, t) }
        setEntries(playlistId, current + added)
    }

    override suspend fun removeEntry(playlistId: Long, entryId: Long) {
        setEntries(playlistId, entries.value[playlistId].orEmpty().filterNot { it.entryId == entryId })
    }

    override suspend fun moveEntry(playlistId: Long, fromPosition: Int, toPosition: Int) {
        moves += Triple(playlistId, fromPosition, toPosition)
        if (failMoves) error("boom")
        val list = entries.value[playlistId].orEmpty().toMutableList()
        list.add(toPosition, list.removeAt(fromPosition))
        setEntries(playlistId, list)
    }

    override suspend fun containsTrack(playlistId: Long, trackId: String): Boolean =
        entries.value[playlistId].orEmpty().any { it.track.id == trackId }

    private fun setEntries(playlistId: Long, list: List<PlaylistEntry>) {
        val reindexed = list.mapIndexed { i, e -> e.copy(position = i) }
        entries.value += playlistId to reindexed
        playlists.value = playlists.value.map { if (it.id == playlistId) it.copy(trackCount = reindexed.size) else it }
    }
}
