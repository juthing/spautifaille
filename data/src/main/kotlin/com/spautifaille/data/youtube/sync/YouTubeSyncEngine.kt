package com.spautifaille.data.youtube.sync

import androidx.room.withTransaction
import com.spautifaille.data.local.PlaylistDao
import com.spautifaille.data.local.PlaylistEntity
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.SubscriptionDao
import com.spautifaille.data.local.SubscriptionEntity
import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.YouTubeSyncDao
import com.spautifaille.data.local.YtEntryLinkEntity
import com.spautifaille.data.local.YtPlaylistLinkEntity
import com.spautifaille.data.local.toEntity
import com.spautifaille.data.youtube.api.RemotePlaylist
import com.spautifaille.data.youtube.api.RemoteTrack
import com.spautifaille.data.youtube.api.YouTubeRemote
import com.spautifaille.data.youtube.session.SyncMetaStore
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.youtube.OrderedListReconciler
import com.spautifaille.domain.youtube.OrderedReconciliation
import com.spautifaille.domain.youtube.RemoteLibraryPlaylist
import com.spautifaille.domain.youtube.SetReconciler
import com.spautifaille.domain.youtube.Slot
import com.spautifaille.domain.youtube.YouTubeChannelIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Une étape de synchronisation qui a échoué (les autres étapes continuent). */
internal data class StepFailure(val step: String, val error: AppError)

/**
 * Synchronisation complète et opérations par playlist liée. Écrit directement dans les DAO (jamais via les
 * repositories) : les changements venus de YouTube ne doivent pas être renvoyés vers YouTube.
 *
 * Ordre de chaque étape : lecture distante → réconciliation à trois voies (domaine) → écritures distantes (réseau,
 * hors transaction) → écritures locales et instantané dans **une** transaction Room. Si une écriture distante
 * échoue, ni les données locales ni l'instantané ne bougent : la prochaine synchro rejoue (opérations idempotentes).
 */
@Singleton
internal class YouTubeSyncEngine internal constructor(
    private val db: SpautifailleDatabase,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val subscriptionDao: SubscriptionDao,
    private val syncDao: YouTubeSyncDao,
    private val remote: YouTubeRemote,
    private val meta: SyncMetaStore,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        db: SpautifailleDatabase,
        trackDao: TrackDao,
        playlistDao: PlaylistDao,
        subscriptionDao: SubscriptionDao,
        syncDao: YouTubeSyncDao,
        remote: YouTubeRemote,
        meta: SyncMetaStore,
    ) : this(db, trackDao, playlistDao, subscriptionDao, syncDao, remote, meta, System::currentTimeMillis)

    // region Synchronisation complète

    /**
     * Likes, abonnements puis playlists liées. Une étape en échec est consignée et les suivantes continuent ; les
     * erreurs qui doivent tout interrompre (reconnexion nécessaire, réseau, throttling) sont propagées.
     */
    suspend fun syncAll(): List<StepFailure> {
        val failures = ArrayList<StepFailure>()
        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                if (e.error == AppError.YouTubeAuthRequired || e.error.isRecoverable) throw e
                failures += StepFailure(name, e.error)
            } catch (e: Exception) {
                failures += StepFailure(name, AppError.Unknown(e.message))
            }
        }

        step("likes") { syncLikes() }
        step("abonnements") { syncSubscriptions() }
        cleanupOrphanSnapshots()
        for (link in syncDao.allLinks()) {
            step("playlist ${link.youtubePlaylistId}") { syncLinkedPlaylist(link.playlistId) }
        }
        return failures
    }

    // endregion

    // region Likes

    private suspend fun syncLikes() {
        val musicOnly = meta.likesMusicOnly.first()
        var mode = if (musicOnly) MODE_MUSIC else MODE_ALL
        val remoteTracks: List<RemoteTrack> = if (musicOnly) {
            remote.likedMusic()
        } else {
            try {
                remote.likedAll()
            } catch (e: AppException) {
                // « Vidéos J'aime » (LL) n'est pas servie par YouTube Music pour ce compte : repli sur « Musique likée ».
                if (e.error !is AppError.YouTubeSyncFailed) throw e
                mode = MODE_MUSIC
                remote.likedMusic()
            }
        }

        val previousMode = syncDao.snapshot(SCOPE_LIKES_MODE)?.firstOrNull()
        val base = syncDao.snapshot(SCOPE_LIKES)?.toSet()
        // Changement de mode : la vue distante n'est plus comparable, rien n'est considéré comme « vu » (donc supprimé).
        val seen = if (previousMode == mode) syncDao.snapshot(SCOPE_LIKES_SEEN)?.toSet() else emptySet()

        val remoteById = remoteTracks.associateBy { it.videoId }
        val local = syncDao.trackIds(Playlist.LIKED_ID).toSet()
        val result = SetReconciler.reconcile(base, local, remoteById.keys, seen)

        result.addRemote.forEach { remote.like(it) }
        result.removeRemote.forEach { remote.unlike(it) }

        db.withTransaction {
            val now = clock()
            // La liste distante est du plus récent au plus ancien : on insère du plus ancien au plus récent.
            val toAdd = remoteTracks.filter { it.videoId in result.addLocal }.reversed()
            ensureTracks(toAdd, now)
            playlistDao.addEntries(Playlist.LIKED_ID, toAdd.map { it.videoId }, now)
            result.removeLocal.forEach { playlistDao.removeTrack(Playlist.LIKED_ID, it, now) }
            syncDao.replaceSnapshot(SCOPE_LIKES, result.finalSet.toList())
            // « Vidéos J'aime » liste tout ce qui est aimé : ce qu'on vient d'y pousser y sera vu à la prochaine lecture.
            val seenAfter = if (mode == MODE_ALL) result.seenAfter + result.addRemote else result.seenAfter
            syncDao.replaceSnapshot(SCOPE_LIKES_SEEN, seenAfter.toList())
            syncDao.replaceSnapshot(SCOPE_LIKES_MODE, listOf(mode))
        }
    }

    // endregion

    // region Abonnements

    private suspend fun syncSubscriptions() {
        val remoteChannels = remote.subscriptions().associateBy { it.channelId }
        val localByChannel = subscriptionDao.observeAll().first()
            .mapNotNull { row -> YouTubeChannelIds.fromUrl(row.url)?.let { it to row } }
            .toMap()
        val base = syncDao.snapshot(SCOPE_SUBS)?.toSet()
        val seen = syncDao.snapshot(SCOPE_SUBS_SEEN)?.toSet()
        val result = SetReconciler.reconcile(base, localByChannel.keys, remoteChannels.keys, seen)

        result.addRemote.forEach { remote.subscribe(it) }
        result.removeRemote.forEach { remote.unsubscribe(it) }

        db.withTransaction {
            val now = clock()
            for (id in result.addLocal) {
                val channel = remoteChannels.getValue(id)
                subscriptionDao.upsert(
                    SubscriptionEntity(
                        url = YouTubeChannelIds.toUrl(id),
                        name = channel.name,
                        avatarUrl = channel.avatarUrl,
                        subscribedAt = now,
                    ),
                )
            }
            for (id in result.removeLocal) localByChannel[id]?.let { subscriptionDao.delete(it.url) }
            syncDao.replaceSnapshot(SCOPE_SUBS, result.finalSet.toList())
            syncDao.replaceSnapshot(SCOPE_SUBS_SEEN, result.seenAfter.toList())
        }
    }

    // endregion

    // region Playlists liées

    /** Playlists de la bibliothèque du compte avec leur état de liaison. */
    suspend fun listRemotePlaylists(): List<RemoteLibraryPlaylist> {
        val linked = syncDao.allLinks().map { it.youtubePlaylistId }.toSet()
        return remote.libraryPlaylists().map {
            RemoteLibraryPlaylist(
                id = it.id,
                title = it.title,
                thumbnailUrl = it.thumbnailUrl,
                trackCount = it.trackCount,
                isOwned = it.isOwned,
                isLinked = it.id in linked,
            )
        }
    }

    /** Importe [remoteId] comme playlist locale liée (ou renvoie la playlist déjà liée). */
    suspend fun importPlaylist(remoteId: String): Long {
        syncDao.linkByYoutubeId(remoteId)?.let { return it.playlistId }
        val content = remote.playlist(remoteId)
        val now = clock()
        val playlistId = db.withTransaction {
            val id = playlistDao.insert(
                PlaylistEntity(
                    name = content.title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PLAYLIST_NAME,
                    isSystem = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            syncDao.upsertLink(
                YtPlaylistLinkEntity(
                    playlistId = id,
                    youtubePlaylistId = remoteId,
                    readOnly = !content.isOwned,
                    linkedAt = now,
                    lastSyncedAt = null,
                ),
            )
            id
        }
        syncLinkedPlaylist(playlistId, prefetched = content)
        return playlistId
    }

    /** Supprime le lien ; la playlist locale et la playlist YouTube restent intactes. */
    suspend fun unlink(playlistId: Long) {
        db.withTransaction {
            syncDao.deleteEntryLinksOf(playlistId)
            syncDao.deleteSnapshot(playlistScope(playlistId))
            syncDao.deleteLink(playlistId)
        }
    }

    /** Crée la playlist locale [playlistId] sur YouTube puis la lie. Sans effet si elle est système ou déjà liée. */
    suspend fun publish(playlistId: Long) {
        if (playlistId == Playlist.LIKED_ID || syncDao.link(playlistId) != null) return
        val playlist = playlistDao.observePlaylist(playlistId).first() ?: return
        if (playlist.isSystem) return
        val videoIds = syncDao.entryRows(playlistId).map { it.trackId }
        val youtubeId = remote.createPlaylist(playlist.name, videoIds)
        syncDao.upsertLink(
            YtPlaylistLinkEntity(
                playlistId = playlistId,
                youtubePlaylistId = youtubeId,
                readOnly = false,
                linkedAt = clock(),
                lastSyncedAt = null,
            ),
        )
        // Premier passage sans instantané : union (identique des deux côtés) ; enregistre setVideoId et instantané.
        syncLinkedPlaylist(playlistId)
    }

    /**
     * Synchronise une playlist liée : ajouts / suppressions des deux côtés, ordre (un seul côté a réordonné → ce
     * côté ; sinon le distant). Playlist en lecture seule : miroir du distant.
     */
    suspend fun syncLinkedPlaylist(playlistId: Long, prefetched: RemotePlaylist? = null) {
        val link = syncDao.link(playlistId) ?: return
        val youtubeId = link.youtubePlaylistId
        val remoteList = prefetched ?: remote.playlist(youtubeId)
        val rows = syncDao.entryRows(playlistId)
        val localIds = rows.map { it.trackId }
        val remoteIds = remoteList.items.map { it.videoId }
        val storedBase = syncDao.snapshot(playlistScope(playlistId))
        val readOnly = link.readOnly || !remoteList.isOwned
        // Lecture seule : les modifications locales ne sont pas poussées, le distant l'emporte (base = état local).
        val base = if (readOnly) localIds else storedBase

        val result = OrderedListReconciler.reconcile(base, localIds, remoteIds)
        val localSlots = slotsOf(localIds)
        val localBySlot = localSlots.zip(rows).toMap()
        val remoteSlots = slotsOf(remoteIds)
        var currentRemote = remoteList.items

        if (!readOnly) {
            if (result.removeRemote.isNotEmpty()) {
                val remoteBySlot = remoteSlots.zip(remoteList.items).toMap()
                val entries = result.removeRemote.map { slot ->
                    val item = remoteBySlot.getValue(slot)
                    val setVideoId = item.setVideoId ?: throw AppException(
                        AppError.YouTubeSyncFailed("browse/edit_playlist", "setVideoId absent pour ${item.videoId}"),
                    )
                    setVideoId to item.videoId
                }
                remote.removeFromPlaylist(youtubeId, entries)
            }
            if (result.addRemote.isNotEmpty()) {
                remote.addToPlaylist(
                    youtubeId,
                    result.addRemote.map { it.item },
                    allowDuplicates = result.addRemote.any { it.occurrence > 0 },
                )
            }
            val remoteChanged = result.removeRemote.isNotEmpty() || result.addRemote.isNotEmpty()
            if (remoteChanged || result.remoteNeedsReorder) {
                // Nouvelles entrées : leurs setVideoId ne sont connus qu'après relecture.
                currentRemote = remote.playlist(youtubeId).items
            }
            if (result.remoteNeedsReorder) currentRemote = reorderRemote(youtubeId, currentRemote, result)
        }

        val currentBySlot = slotsOf(currentRemote.map { it.videoId }).zip(currentRemote).toMap()

        db.withTransaction {
            val now = clock()
            val rowsNow = syncDao.entryRows(playlistId)
            // Modification locale pendant la synchro : on laisse la suivante (déjà en file) trancher.
            if (rowsNow.map { it.entryId to it.trackId } != rows.map { it.entryId to it.trackId }) return@withTransaction

            for (slot in result.removeLocal) playlistDao.removeEntry(playlistId, localBySlot.getValue(slot).entryId, now)
            val toAdd = result.addLocal.mapNotNull { currentBySlot[it] }
            ensureTracks(toAdd, now)
            playlistDao.addEntries(playlistId, toAdd.map { it.videoId }, now)

            val after = syncDao.entryRows(playlistId)
            val afterBySlot = slotsOf(after.map { it.trackId }).zip(after).toMap()
            if (afterBySlot.keys == result.finalOrder.toSet()) {
                if (result.localNeedsReorder) {
                    result.finalOrder.forEachIndexed { index, slot ->
                        playlistDao.setPosition(afterBySlot.getValue(slot).entryId, index)
                    }
                }
                syncDao.deleteEntryLinksOf(playlistId)
                syncDao.upsertEntryLinks(
                    result.finalOrder.mapNotNull { slot ->
                        val setVideoId = currentBySlot[slot]?.setVideoId ?: return@mapNotNull null
                        YtEntryLinkEntity(afterBySlot.getValue(slot).entryId, setVideoId)
                    },
                )
                syncDao.replaceSnapshot(playlistScope(playlistId), result.finalOrder.map { it.item })
                syncDao.markSynced(playlistId, now)
            }
        }
    }

    /** Applique les déplacements nécessaires côté distant ; renvoie la liste distante dans l'ordre final. */
    private suspend fun reorderRemote(
        youtubeId: String,
        current: List<RemoteTrack>,
        result: OrderedReconciliation<String>,
    ): List<RemoteTrack> {
        val currentSlots = slotsOf(current.map { it.videoId })
        // Contenu différent (modification concurrente, ajout refusé) : on ne réordonne pas, la prochaine synchro reprend.
        if (currentSlots.toSet() != result.finalOrder.toSet() || currentSlots.size != result.finalOrder.size) return current
        val bySlot = currentSlots.zip(current).toMap()
        for (move in OrderedListReconciler.moves(currentSlots, result.finalOrder)) {
            val moved = bySlot.getValue(move.item).setVideoId
            val before = bySlot.getValue(move.before).setVideoId
            if (moved == null || before == null) {
                throw AppException(AppError.YouTubeSyncFailed("browse/edit_playlist", "setVideoId absent pour un déplacement"))
            }
            remote.moveInPlaylist(youtubeId, moved, before)
        }
        return result.finalOrder.map { bySlot.getValue(it) }
    }

    /** Instantanés de playlists qui ne sont plus liées (playlist supprimée) : à purger. */
    private suspend fun cleanupOrphanSnapshots() {
        val linked = syncDao.allLinks().map { playlistScope(it.playlistId) }.toSet()
        syncDao.playlistSnapshotScopes().filter { it !in linked }.forEach { syncDao.deleteSnapshot(it) }
    }

    // endregion

    // region Utilitaires

    /** Insère les titres inconnus localement ; les métadonnées déjà présentes (souvent plus riches) sont conservées. */
    private suspend fun ensureTracks(tracks: List<RemoteTrack>, now: Long) {
        if (tracks.isEmpty()) return
        val known = tracks.map { it.videoId }.distinct().chunked(CHUNK).flatMap { ids -> trackDao.getAll(ids).map { it.id } }.toSet()
        val missing = tracks.filter { it.videoId !in known }.distinctBy { it.videoId }
        if (missing.isNotEmpty()) trackDao.upsertAll(missing.map { it.toTrack().toEntity(now) })
    }

    private fun RemoteTrack.toTrack() = Track(
        id = videoId,
        title = title,
        artist = artist.orEmpty(),
        artistUrl = artistChannelId?.let(YouTubeChannelIds::toUrl),
        album = album,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
    )

    private fun slotsOf(ids: List<String>): List<Slot<String>> {
        val counts = HashMap<String, Int>()
        return ids.map { id ->
            val n = counts.getOrDefault(id, 0)
            counts[id] = n + 1
            Slot(id, n)
        }
    }

    // endregion

    companion object {
        const val SCOPE_LIKES = "LIKES"
        const val SCOPE_LIKES_SEEN = "LIKES_SEEN"
        const val SCOPE_LIKES_MODE = "LIKES_MODE"
        const val SCOPE_SUBS = "SUBS"
        const val SCOPE_SUBS_SEEN = "SUBS_SEEN"
        const val MODE_MUSIC = "LM"
        const val MODE_ALL = "LL"
        private const val CHUNK = 500
        private const val DEFAULT_PLAYLIST_NAME = "Playlist YouTube"

        fun playlistScope(playlistId: Long) = "PL:$playlistId"
    }
}
