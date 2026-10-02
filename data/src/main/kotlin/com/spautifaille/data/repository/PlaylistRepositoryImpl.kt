package com.spautifaille.data.repository

import androidx.room.withTransaction
import com.spautifaille.data.local.PlaylistDao
import com.spautifaille.data.local.PlaylistEntity
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.toDomain
import com.spautifaille.data.local.toEntity
import com.spautifaille.data.youtube.sync.RemoteSyncRecorder
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistWithTracks
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class PlaylistRepositoryImpl internal constructor(
    private val db: SpautifailleDatabase,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val clock: () -> Long,
) : PlaylistRepository {

    /** Propage les modifications des playlists liées et des likes vers le compte YouTube (sans compte : sans effet). */
    internal var remoteSync: RemoteSyncRecorder = RemoteSyncRecorder.None

    @Inject
    constructor(
        db: SpautifailleDatabase,
        playlistDao: PlaylistDao,
        trackDao: TrackDao,
        remoteSync: RemoteSyncRecorder,
    ) : this(db, playlistDao, trackDao, System::currentTimeMillis) {
        this.remoteSync = remoteSync
    }

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylists().map { rows -> rows.map { it.toDomain() } }

    override fun observePlaylist(id: Long): Flow<PlaylistWithTracks?> =
        combine(playlistDao.observePlaylist(id), playlistDao.observeEntries(id)) { row, entries ->
            row?.let {
                PlaylistWithTracks(
                    playlist = it.toDomain(trackCount = entries.size),
                    entries = entries.map { entry -> entry.toDomain() },
                )
            }
        }

    override suspend fun create(name: String, tracks: List<Track>): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Le nom de la playlist ne peut pas être vide" }
        return db.withTransaction {
            val now = clock()
            val id = playlistDao.insert(
                PlaylistEntity(name = cleanName, isSystem = false, createdAt = now, updatedAt = now),
            )
            addTracksInTransaction(id, tracks, now)
            id
        }
    }

    override suspend fun rename(id: Long, name: String) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Le nom de la playlist ne peut pas être vide" }
        playlistDao.rename(id, cleanName, clock())
    }

    override suspend fun delete(id: Long) {
        playlistDao.delete(id)
    }

    override suspend fun addTracks(playlistId: Long, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        db.withTransaction { addTracksInTransaction(playlistId, tracks, clock()) }
        if (playlistId == Playlist.LIKED_ID) {
            tracks.distinctBy { it.id }.forEach { remoteSync.likeChanged(it.id, true) }
        } else {
            remoteSync.playlistChanged(playlistId)
        }
    }

    override suspend fun removeEntry(playlistId: Long, entryId: Long) {
        val removedTrackId = if (playlistId == Playlist.LIKED_ID) playlistDao.trackIdOfEntry(playlistId, entryId) else null
        playlistDao.removeEntry(playlistId, entryId, clock())
        if (playlistId == Playlist.LIKED_ID) {
            // Retirer le dernier exemplaire = ne plus aimer le titre.
            removedTrackId?.takeUnless { playlistDao.contains(playlistId, it) }?.let { remoteSync.likeChanged(it, false) }
        } else {
            remoteSync.playlistChanged(playlistId)
        }
    }

    override suspend fun moveEntry(playlistId: Long, fromPosition: Int, toPosition: Int) {
        playlistDao.moveEntry(playlistId, fromPosition, toPosition, clock())
        if (playlistId != Playlist.LIKED_ID) remoteSync.playlistChanged(playlistId)
    }

    override suspend fun containsTrack(playlistId: Long, trackId: String): Boolean =
        playlistDao.contains(playlistId, trackId)

    private suspend fun addTracksInTransaction(playlistId: Long, tracks: List<Track>, now: Long) {
        if (tracks.isEmpty()) return
        trackDao.upsertAll(tracks.distinctBy { it.id }.map { it.toEntity(now) })
        playlistDao.addEntries(playlistId, tracks.map { it.id }, now)
    }
}
