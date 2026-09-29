package com.spautifaille.data.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.repository.PlaylistRepositoryImpl
import com.spautifaille.data.repository.TrackCacheImpl
import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.matching.TrackMatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first

/** Base Room en mémoire + repositories réels, réseau simulé. */
internal class ImportEnv {
    var now = 1_000L
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: SpautifailleDatabase = createInMemoryDatabase(context)
    val playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao()) { ++now }
    val trackCache = TrackCacheImpl(db.trackDao()) { ++now }
    val stream = FakeStreamRepository()
    val scheduler = FakeScheduler()
    val store = ImportStore(db, db.importDao(), db.trackDao(), db.playlistDao()) { ++now }
    val matcher = TrackMatcher()

    fun processor(throttleMs: Long = 300L, botBackoffMs: Long = 30_000L) =
        ImportProcessor(store, db.importDao(), stream, trackCache, matcher, throttleMs, botBackoffMs)

    fun repository(importers: List<com.spautifaille.domain.importer.PlaylistImporter>, io: CoroutineDispatcher) =
        ImportRepositoryImpl(context, db, db.importDao(), db.trackDao(), playlists, PlaylistImporters(importers), store, scheduler, io)

    /** Crée une playlist cible vide et un job PENDING pour [tracks]. Renvoie (jobId, playlistId). */
    suspend fun newJob(tracks: List<ImportedTrack>, name: String = "Import"): Pair<Long, Long> {
        val playlistId = playlists.create(name)
        val jobId = store.createJob(ImportedPlaylist(name, tracks, ImportFormat.GENERIC_CSV), playlistId)
        return jobId to playlistId
    }

    suspend fun playlistTrackIds(playlistId: Long): List<String> =
        playlists.observePlaylist(playlistId).first()!!.entries.map { it.track.id }

    suspend fun playlistPositions(playlistId: Long): List<Int> =
        playlists.observePlaylist(playlistId).first()!!.entries.map { it.position }

    fun close() = db.close()
}

internal fun imported(title: String, artist: String = "Artiste", durationMs: Long? = 200_000L, youtubeId: String? = null) =
    ImportedTrack(title = title, artists = listOf(artist), durationMs = durationMs, youtubeId = youtubeId)
