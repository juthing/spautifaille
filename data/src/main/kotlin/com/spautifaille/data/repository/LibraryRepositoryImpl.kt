package com.spautifaille.data.repository

import androidx.room.withTransaction
import com.spautifaille.data.local.HistoryDao
import com.spautifaille.data.local.HistoryEntity
import com.spautifaille.data.local.PlaylistDao
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.SubscriptionDao
import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.toDomain
import com.spautifaille.data.local.toEntity
import com.spautifaille.data.youtube.sync.RemoteSyncRecorder
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.PlayCount
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class LibraryRepositoryImpl internal constructor(
    private val db: SpautifailleDatabase,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao,
    private val subscriptionDao: SubscriptionDao,
    private val clock: () -> Long,
) : LibraryRepository {

    /** Propage likes et abonnements vers le compte YouTube (sans effet si aucun compte n'est connecté). */
    internal var remoteSync: RemoteSyncRecorder = RemoteSyncRecorder.None

    @Inject
    constructor(
        db: SpautifailleDatabase,
        trackDao: TrackDao,
        playlistDao: PlaylistDao,
        historyDao: HistoryDao,
        subscriptionDao: SubscriptionDao,
        remoteSync: RemoteSyncRecorder,
    ) : this(db, trackDao, playlistDao, historyDao, subscriptionDao, System::currentTimeMillis) {
        this.remoteSync = remoteSync
    }

    private val likedId = Playlist.LIKED_ID

    // region Likes

    override fun observeLikedIds(): Flow<Set<String>> =
        playlistDao.observeTrackIds(likedId).map { it.toSet() }.distinctUntilChanged()

    override fun observeIsLiked(trackId: String): Flow<Boolean> =
        playlistDao.observeContains(likedId, trackId).distinctUntilChanged()

    override suspend fun isLiked(trackId: String): Boolean = playlistDao.contains(likedId, trackId)

    override suspend fun toggleLike(track: Track): Boolean {
        val liked = db.withTransaction {
            val liked = !playlistDao.contains(likedId, track.id)
            applyLike(track, liked)
            liked
        }
        remoteSync.likeChanged(track.id, liked)
        return liked
    }

    override suspend fun setLiked(track: Track, liked: Boolean) {
        val changed = db.withTransaction {
            val changed = playlistDao.contains(likedId, track.id) != liked
            if (changed) applyLike(track, liked)
            changed
        }
        if (changed) remoteSync.likeChanged(track.id, liked)
    }

    /** Doit être appelé dans une transaction. */
    private suspend fun applyLike(track: Track, liked: Boolean) {
        val now = clock()
        if (liked) {
            trackDao.upsertAll(listOf(track.toEntity(now)))
            playlistDao.addEntries(likedId, listOf(track.id), now)
        } else {
            playlistDao.removeTrack(likedId, track.id, now)
        }
    }

    override suspend fun recentlyLiked(limit: Int): List<Track> =
        playlistDao.latestTracks(likedId, limit).map { it.toDomain() }

    override suspend fun libraryTrackIds(): Set<String> = playlistDao.allTrackIds().toSet()

    // endregion

    // region History

    override suspend fun recordPlay(track: Track, playedAt: Long) {
        db.withTransaction {
            trackDao.upsertAll(listOf(track.toEntity(clock())))
            historyDao.insert(HistoryEntity(trackId = track.id, playedAt = playedAt))
        }
    }

    override fun observeHistory(limit: Int): Flow<List<HistoryEntry>> =
        historyDao.observeLatest(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun removeHistoryEntry(id: Long) = historyDao.delete(id)

    override suspend fun clearHistory() = historyDao.clear()

    override suspend fun recentlyPlayedIds(sinceMs: Long): Set<String> = historyDao.recentIds(sinceMs).toSet()

    override suspend fun mostPlayed(limit: Int, sinceMs: Long): List<PlayCount> =
        historyDao.mostPlayed(limit, sinceMs).map { it.toDomain() }

    // endregion

    // region Subscriptions

    override fun observeSubscriptions(): Flow<List<Artist>> =
        subscriptionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeIsSubscribed(artistUrl: String): Flow<Boolean> =
        subscriptionDao.observeIsSubscribed(artistUrl).distinctUntilChanged()

    override suspend fun subscribe(artist: Artist) {
        subscriptionDao.upsert(artist.toEntity(clock()))
        remoteSync.subscriptionChanged(artist.url, true)
    }

    override suspend fun unsubscribe(artistUrl: String) {
        subscriptionDao.delete(artistUrl)
        remoteSync.subscriptionChanged(artistUrl, false)
    }

    // endregion
}
