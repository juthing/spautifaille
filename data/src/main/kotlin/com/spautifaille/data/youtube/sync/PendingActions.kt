package com.spautifaille.data.youtube.sync

import com.spautifaille.data.local.YouTubeSyncDao
import com.spautifaille.data.local.YtPendingActionEntity
import com.spautifaille.data.youtube.api.YouTubeRemote
import com.spautifaille.data.youtube.session.YouTubeSessionStore
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.youtube.YouTubeChannelIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Types d'actions de la file persistée (`yt_pending_actions.kind`). */
internal object ActionKind {
    const val LIKE = "LIKE"
    const val SUBSCRIPTION = "SUBSCRIPTION"

    /** Synchronise une playlist liée (ajouts, suppressions, déplacements) : la réconciliation pousse les différences. */
    const val PLAYLIST_SYNC = "PLAYLIST_SYNC"
    const val PLAYLIST_PUBLISH = "PLAYLIST_PUBLISH"
}

/**
 * Enfile les écritures distantes déclenchées par l'utilisateur (like, abonnement, modification d'une playlist
 * liée) quand un compte est connecté, puis planifie leur envoi. Appelé par les repositories **après** leur écriture
 * locale ; ne lève jamais (la fonctionnalité locale ne doit pas dépendre du compte).
 */
@Singleton
internal class PendingActionRecorder internal constructor(
    private val sessions: YouTubeSessionStore,
    private val syncDao: YouTubeSyncDao,
    private val scheduler: SyncScheduler,
    private val clock: () -> Long,
) : RemoteSyncRecorder {

    @Inject
    constructor(
        sessions: YouTubeSessionStore,
        syncDao: YouTubeSyncDao,
        scheduler: SyncScheduler,
    ) : this(sessions, syncDao, scheduler, System::currentTimeMillis)

    override suspend fun likeChanged(videoId: String, liked: Boolean) {
        enqueue(ActionKind.LIKE, videoId, liked)
    }

    override suspend fun subscriptionChanged(artistUrl: String, subscribed: Boolean) {
        // Les abonnements locaux dont l'URL n'est pas de la forme /channel/UC… (ex. @handle) ne sont pas synchronisables.
        val channelId = YouTubeChannelIds.fromUrl(artistUrl) ?: return
        enqueue(ActionKind.SUBSCRIPTION, channelId, subscribed)
    }

    override suspend fun playlistChanged(playlistId: Long) {
        if (playlistId == Playlist.LIKED_ID) return
        val link = try {
            syncDao.link(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        // Lecture seule : les modifications locales ne sont jamais poussées (le distant l'emporte à la synchro).
        if (link.readOnly) return
        enqueue(ActionKind.PLAYLIST_SYNC, playlistId.toString(), true)
    }

    private suspend fun enqueue(kind: String, target: String, enabled: Boolean) {
        try {
            if (!sessions.isLinked()) return
            syncDao.enqueue(kind, target, enabled, clock())
            scheduler.scheduleFlush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Une action perdue est rattrapée par la synchronisation complète (réconciliation à trois voies).
        }
    }
}

/** Rejoue la file d'actions persistée. */
@Singleton
internal class PendingActionProcessor @Inject constructor(
    private val syncDao: YouTubeSyncDao,
    private val remote: YouTubeRemote,
    private val engine: YouTubeSyncEngine,
) {
    /**
     * Envoie les actions dans l'ordre. Une erreur qui doit tout interrompre (reconnexion nécessaire, réseau,
     * throttling) est propagée et l'action reste en file ; toute autre erreur est consignée sur l'action, qui est
     * abandonnée après [MAX_ATTEMPTS] essais (la synchro complète rattrape de toute façon la différence).
     */
    suspend fun flush(): List<StepFailure> {
        val failures = ArrayList<StepFailure>()
        for (action in syncDao.pendingActions()) {
            try {
                execute(action)
                syncDao.deleteActionById(action.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                syncDao.recordFailure(action.id, e.error.toString().take(200))
                if (e.error == AppError.YouTubeAuthRequired || e.error.isRecoverable) throw e
                if (action.attempts + 1 >= MAX_ATTEMPTS) syncDao.deleteActionById(action.id)
                failures += StepFailure("${action.kind} ${action.target}", e.error)
            }
        }
        return failures
    }

    private suspend fun execute(action: YtPendingActionEntity) {
        when (action.kind) {
            ActionKind.LIKE -> if (action.enabled) remote.like(action.target) else remote.unlike(action.target)
            ActionKind.SUBSCRIPTION ->
                if (action.enabled) remote.subscribe(action.target) else remote.unsubscribe(action.target)
            ActionKind.PLAYLIST_SYNC -> action.target.toLongOrNull()?.let { engine.syncLinkedPlaylist(it) }
            ActionKind.PLAYLIST_PUBLISH -> action.target.toLongOrNull()?.let { engine.publish(it) }
            else -> Unit
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}
