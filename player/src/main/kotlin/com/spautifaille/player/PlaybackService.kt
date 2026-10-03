package com.spautifaille.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.spautifaille.domain.di.ApplicationScope
import com.spautifaille.domain.di.DefaultDispatcher
import com.spautifaille.domain.player.PlayerEvent
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.LoudnessStore
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.domain.repository.QueueStateStore
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.domain.repository.TrackCache
import com.spautifaille.player.artwork.LandscapeArtworkBitmapLoader
import com.spautifaille.player.datasource.PlayerDataSourceFactory
import com.spautifaille.player.datasource.StreamLoadErrorHandlingPolicy
import com.spautifaille.player.datasource.StreamResolver
import com.spautifaille.player.error.ConnectivityObserver
import com.spautifaille.player.error.PlaybackErrorHandler
import com.spautifaille.player.history.HistoryAwarePlayer
import com.spautifaille.player.history.PlayHistoryRecorder
import com.spautifaille.player.history.SessionHistory
import com.spautifaille.player.library.LibraryBrowseTree
import com.spautifaille.player.library.SiblingExpansion
import com.spautifaille.player.queue.QueuePersister
import com.spautifaille.player.queue.QueueSnapshots
import com.spautifaille.player.queue.toPlayerRepeatMode
import com.spautifaille.player.session.SessionStatePublisher
import com.spautifaille.player.sleep.SleepTimerManager
import com.spautifaille.player.sleep.SleepTimerPlayer
import com.spautifaille.player.volume.ExoVolumeOutput
import com.spautifaille.player.volume.LoudnessGainController
import com.spautifaille.player.volume.PlaybackVolume
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Service de lecture : possède l'[ExoPlayer] et la [androidx.media3.session.MediaLibrarySession].
 * L'UI, la notification, l'écran de verrouillage, Android Auto et les boutons média passent tous par la session.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var dataSourceFactory: PlayerDataSourceFactory
    @Inject lateinit var streamResolver: StreamResolver
    @Inject lateinit var streams: StreamRepository
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var downloads: DownloadRepository
    @Inject lateinit var trackCache: TrackCache
    @Inject lateinit var loudnessStore: LoudnessStore
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var queueStore: QueueStateStore
    @Inject lateinit var connectivity: ConnectivityObserver
    @Inject @field:ApplicationScope lateinit var appScope: CoroutineScope
    @Inject @field:DefaultDispatcher lateinit var defaultDispatcher: CoroutineDispatcher

    /** Portée liée au service, sur le thread principal (celui du lecteur). Annulée dans [onDestroy]. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var browseTree: LibraryBrowseTree? = null
    private var publisher: SessionStatePublisher? = null
    private var sleepTimer: SleepTimerManager? = null
    private var persister: QueuePersister? = null
    private var errorHandler: PlaybackErrorHandler? = null
    private var historyRecorder: PlayHistoryRecorder? = null
    private var volumeOutput: ExoVolumeOutput? = null
    private var gainController: LoudnessGainController? = null

    /** Titres réellement écoutés pendant la session : « précédent » y revient même si la file a été remplacée. */
    private var sessionHistory: SessionHistory? = null
    private var artworkLoader: LandscapeArtworkBitmapLoader? = null

    /** Résultats de la dernière recherche, pour `onGetSearchResult` (petit cache LRU, thread principal). */
    private val searchResults = object : LinkedHashMap<String, List<MediaItem>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) = size > MAX_CACHED_SEARCHES
    }

    override fun onCreate() {
        super.onCreate()
        val exo = buildPlayer()
        player = exo
        val tree = LibraryBrowseTree(this, library, playlists, streams, trackCache)
        browseTree = tree

        // Alimenté avant la création de la session pour ne rater aucune transition.
        val listened = SessionHistory(onChanged = { publisher?.onHistoryAvailable(sessionHistory?.isEmpty == false) })
        sessionHistory = listened
        exo.addListener(listened)

        val landscapeArtwork = LandscapeArtworkBitmapLoader(
            delegate = DataSourceBitmapLoader.Builder(this).setMaximumOutputDimension(ARTWORK_MAX_SOURCE_PX).build(),
            dispatcher = defaultDispatcher,
        )
        artworkLoader = landscapeArtwork
        val mediaSession = MediaLibrarySession.Builder(this, HistoryAwarePlayer(exo, listened), SessionCallback(exo, tree))
            // Image du lecteur système (verrouillage, notification) : affiche paysage 16:9, `artworkUri` restant carré pour l'UI.
            .setBitmapLoader(landscapeArtwork)
            .apply { buildSessionActivity()?.let(::setSessionActivity) }
            .build()
        session = mediaSession
        addSession(mediaSession)

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.player_notification_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        val statePublisher = SessionStatePublisher(this, mediaSession, library, downloads, serviceScope)
        publisher = statePublisher
        listened.reset(exo.currentMediaItem)

        // Volume de sortie = fondu de la minuterie × gain « volume égal » du titre courant (voir PlaybackVolume).
        val output = ExoVolumeOutput(exo).also { it.start() }
        volumeOutput = output
        val playbackVolume = PlaybackVolume(output)
        startVolumeNormalization(exo, playbackVolume)

        val timer = SleepTimerManager(
            scope = serviceScope,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            player = ExoSleepTimerPlayer(exo, playbackVolume),
            onStateChanged = statePublisher::onSleepTimerChanged,
            onFinished = { broadcastEvent(PlayerEvent.SleepTimerFinished) },
        )
        sleepTimer = timer

        val queuePersister = QueuePersister(exo, queueStore, serviceScope, appScope)
        persister = queuePersister
        val errors = PlaybackErrorHandler(
            player = exo,
            scope = serviceScope,
            connectivity = connectivity,
            invalidateStream = streamResolver::invalidate,
            emit = ::broadcastEvent,
            clock = SystemClock::elapsedRealtime,
        )
        errorHandler = errors
        val history = PlayHistoryRecorder(
            player = exo,
            scope = serviceScope,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            record = { track -> appScope.launch { runCatching { library.recordPlay(track) } } },
        )
        historyRecorder = history

        exo.addListener(StateListener(exo, statePublisher, timer))
        exo.addListener(queuePersister)
        exo.addListener(errors)
        exo.addListener(history)

        statePublisher.start()
        statePublisher.onCurrentMediaId(exo.currentMediaItem?.mediaId)
        restoreQueue(exo, queuePersister)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        persister?.flushNow()
        // Lecture en cours : elle continue (service de premier plan). Sinon on libère le service.
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        persister?.flushNow()
        persister?.release()
        errorHandler?.release()
        historyRecorder?.release()
        artworkLoader?.release()
        sleepTimer?.cancel()
        publisher?.stop()
        gainController?.release()
        volumeOutput?.release()
        serviceScope.cancel()
        session?.let { mediaSession ->
            removeSession(mediaSession)
            mediaSession.release()
        }
        player?.release()
        session = null
        player = null
        // Le SimpleCache est un singleton de process : il n'est volontairement pas libéré ici.
        super.onDestroy()
    }

    // --- Construction ---------------------------------------------------------------------------------------------

    private fun buildPlayer(): ExoPlayer {
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(StreamLoadErrorHandlingPolicy(streamResolver))
        // Démarrage rapide pour l'audio : 1 s de tampon suffit pour lancer la lecture (2,5 s après une rebuffer).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMaxSeekToPreviousPositionMs(SEEK_TO_PREVIOUS_MAX_MS)
            .build()
            .apply {
                // Précharge ~10 s (la valeur est en microsecondes) du titre suivant : résolution + ouverture anticipées.
                preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_DURATION_US)
            }
    }

    /**
     * « Volume égal entre les titres » : suit le réglage à chaud. Le niveau d'un titre vient du [LoudnessStore] ;
     * à défaut (titre téléchargé avant la fonction, par exemple) on le demande à la résolution du flux, une fois.
     */
    private fun startVolumeNormalization(exo: ExoPlayer, volume: PlaybackVolume) {
        val controller = LoudnessGainController(
            player = exo,
            scope = serviceScope,
            volume = volume,
            peek = loudnessStore::peek,
            lookup = { videoId -> loudnessStore.get(videoId) ?: streamResolver.resolve(videoId).loudnessDb },
        )
        gainController = controller
        controller.start()
        serviceScope.launch {
            settings.settings.map { it.normalizeVolume }.distinctUntilChanged().collect(controller::setEnabled)
        }
    }

    private fun buildSessionActivity(): PendingIntent? {
        // Pas de dépendance à :app : on lance l'activité de démarrage du package.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        return PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Recharge la dernière file (sans préparer : aucun accès réseau tant que l'utilisateur ne lance pas la lecture). */
    private fun restoreQueue(exo: ExoPlayer, queuePersister: QueuePersister) {
        serviceScope.launch {
            try {
                val snapshot = runCatching { queueStore.load() }.getOrNull()
                // Un contrôleur a pu démarrer une lecture entre-temps : on ne l'écrase pas.
                if (snapshot != null && exo.mediaItemCount == 0) QueueSnapshots.restore(exo, snapshot)
            } finally {
                queuePersister.arm()
            }
        }
    }

    private fun broadcastEvent(event: PlayerEvent) {
        session?.broadcastCustomCommand(SessionContract.eventCommand, SessionContract.encodeEvent(event))
    }

    // --- Listeners ------------------------------------------------------------------------------------------------

    /** Relie le lecteur au publieur d'état (titre courant) et à la minuterie de sommeil. */
    private class StateListener(
        private val player: Player,
        private val publisher: SessionStatePublisher,
        private val sleepTimer: SleepTimerManager,
    ) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            publisher.onCurrentMediaId(mediaItem?.mediaId)

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) =
            publisher.onCurrentMediaId(player.currentMediaItem?.mediaId)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                sleepTimer.onPlaybackResumed()
            } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                sleepTimer.onItemEnded()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) sleepTimer.onItemEnded()
        }
    }

    private class ExoSleepTimerPlayer(private val player: ExoPlayer, private val mix: PlaybackVolume) : SleepTimerPlayer {
        override fun pause() = player.pause()

        /** Fondu de la minuterie : se compose avec le gain de normalisation au lieu de le remplacer. */
        override var volume: Float
            get() = mix.fade
            set(value) {
                mix.fade = value
            }

        override fun setPauseAtEndOfMediaItems(enabled: Boolean) = player.setPauseAtEndOfMediaItems(enabled)
    }

    // --- Callback de session --------------------------------------------------------------------------------------

    private inner class SessionCallback(
        private val exo: ExoPlayer,
        private val tree: LibraryBrowseTree,
    ) : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            val commands = defaults.availableSessionCommands.buildUpon()
                .add(SessionContract.toggleLikeCommand)
                .add(SessionContract.setSleepTimerCommand)
                .add(SessionContract.cancelSleepTimerCommand)
                .add(SessionContract.eventCommand)
                .add(SessionContract.playNextCommand)
                .build()
            // Bouton like (media button preferences) et extras : repris automatiquement de la session.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            SessionContract.ACTION_TOGGLE_LIKE -> serviceScope.future {
                val item = exo.currentMediaItem
                if (item == null) {
                    SessionResult(SessionError.ERROR_INVALID_STATE)
                } else {
                    library.toggleLike(MediaItemMapper.toTrack(item))
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            SessionContract.ACTION_SET_SLEEP_TIMER -> {
                val timer = sleepTimer
                val durationMs = args.getLong(SessionContract.KEY_DURATION_MS, 0L)
                when {
                    timer == null -> immediateResult(SessionError.ERROR_INVALID_STATE)
                    args.getBoolean(SessionContract.KEY_END_OF_TRACK) -> {
                        timer.startEndOfTrack()
                        immediateResult(SessionResult.RESULT_SUCCESS)
                    }
                    durationMs > 0 -> {
                        timer.start(durationMs)
                        immediateResult(SessionResult.RESULT_SUCCESS)
                    }
                    else -> immediateResult(SessionError.ERROR_BAD_VALUE)
                }
            }
            SessionContract.ACTION_PLAY_NEXT -> {
                val tracks = SessionContract.decodePlayNextArgs(args)
                if (tracks.isEmpty()) {
                    immediateResult(SessionError.ERROR_BAD_VALUE)
                } else {
                    QueueCommands.playNext(exo, MediaItemMapper.toMediaItems(tracks))
                    immediateResult(SessionResult.RESULT_SUCCESS)
                }
            }
            SessionContract.ACTION_CANCEL_SLEEP_TIMER -> {
                sleepTimer?.cancel()
                immediateResult(SessionResult.RESULT_SUCCESS)
            }
            else -> immediateResult(SessionError.ERROR_NOT_SUPPORTED)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future { resolveMediaItems(tree, mediaItems).toMutableList() }

        /**
         * Android Auto & co : toucher un titre dans un dossier n'envoie que ce titre. S'il porte l'identifiant de son
         * dossier d'origine, on charge tout le dossier et on démarre au titre touché.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            val expanded = SiblingExpansion.expand(mediaItems, startPositionMs, tree::tracksFor)
            expanded ?: MediaSession.MediaItemsWithStartPosition(
                resolveMediaItems(tree, mediaItems),
                startIndex,
                startPositionMs,
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            val snapshot = queueStore.load() ?: throw UnsupportedOperationException("Aucune file à reprendre")
            exo.repeatMode = snapshot.repeatMode.toPlayerRepeatMode()
            exo.shuffleModeEnabled = snapshot.shuffleEnabled
            MediaSession.MediaItemsWithStartPosition(
                snapshot.tracks.map { MediaItemMapper.toMediaItem(it) },
                snapshot.currentIndex.coerceIn(0, (snapshot.tracks.size - 1).coerceAtLeast(0)),
                snapshot.positionMs,
            )
        }

        // --- Navigation (Android Auto & co) ---

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = if (params?.isRecent == true) tree.recentRootItem else tree.rootItem
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = libraryFuture {
            tree.getItem(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
            val children = tree.getChildren(parentId)
                ?: return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(paginate(children, page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = libraryFuture {
            val items = search(tree, query)
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
            val items = searchResults[query] ?: search(tree, query)
            LibraryResult.ofItemList(paginate(items, page, pageSize), params)
        }
    }

    // --- Utilitaires ----------------------------------------------------------------------------------------------

    private suspend fun search(tree: LibraryBrowseTree, query: String): List<MediaItem> {
        val items = tree.search(query).map { MediaItemMapper.toMediaItem(it) }
        searchResults[query] = items
        return items
    }

    /**
     * Complète les items reçus de contrôleurs externes (Android Auto, Assistant, système) : un item sans
     * `localConfiguration` (id seul, ou requête de recherche vocale) est reconstruit via le cache de titres / le
     * [StreamRepository] ; un id de conteneur (playlist, likés...) est développé en ses titres.
     */
    private suspend fun resolveMediaItems(tree: LibraryBrowseTree, items: List<MediaItem>): List<MediaItem> =
        items.flatMap { item ->
            when {
                item.localConfiguration != null -> listOf(MediaItemMapper.ensureUid(item))
                item.mediaId.isNotEmpty() && tree.isContainerId(item.mediaId) ->
                    tree.tracksFor(item.mediaId).orEmpty().map { MediaItemMapper.toMediaItem(it) }
                item.mediaId.isNotEmpty() ->
                    listOfNotNull(tree.loadTrack(item.mediaId)?.let { MediaItemMapper.toMediaItem(it) })
                !item.requestMetadata.searchQuery.isNullOrBlank() ->
                    tree.search(item.requestMetadata.searchQuery.orEmpty()).map { MediaItemMapper.toMediaItem(it) }
                else -> emptyList()
            }
        }

    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> =
        serviceScope.future<LibraryResult<T>> {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val error: LibraryResult<T> = LibraryResult.ofError<T>(SessionError.ERROR_IO)
                error
            }
        }

    private fun immediateResult(code: Int): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(code))

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return items
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt())
    }

    private companion object {
        const val SEEK_TO_PREVIOUS_MAX_MS = 3_000L
        const val PRELOAD_DURATION_US = 10_000_000L

        /** Les sources d'affiche (maxresdefault 1280 px, pochette 1200 px) sont décodées sans sous-échantillonnage. */
        const val ARTWORK_MAX_SOURCE_PX = 1_280
        const val MAX_CACHED_SEARCHES = 4
    }
}
