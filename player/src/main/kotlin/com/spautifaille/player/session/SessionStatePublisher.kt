package com.spautifaille.player.session

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.player.R
import com.spautifaille.player.SessionContract
import com.spautifaille.player.sleep.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publie vers les contrôleurs l'état propre au service : titre courant aimé / téléchargé, minuterie de sommeil
 * (extras de session), et met à jour le bouton « like » des préférences de boutons média (notification, Android Auto,
 * écran de verrouillage).
 */
@OptIn(UnstableApi::class)
class SessionStatePublisher(
    private val context: Context,
    private val session: MediaSession,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val scope: CoroutineScope,
) {
    private data class Snapshot(val hasItem: Boolean, val state: SessionContract.PublishedState)

    private val currentMediaId = MutableStateFlow<String?>(null)
    private val sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    private var job: Job? = null
    private var lastButtonKey: Pair<Boolean, Boolean>? = null

    /** État initial (utilisé par `onConnect` pour les contrôleurs qui se connectent avant la première émission). */
    var current: SessionContract.PublishedState = SessionContract.PublishedState()
        private set

    fun start() {
        publishButtons(hasItem = false, liked = false)
        job = scope.launch {
            currentMediaId
                .flatMapLatest { id ->
                    if (id == null) {
                        sleepTimer.map { Snapshot(false, SessionContract.PublishedState(sleepTimer = it)) }
                    } else {
                        combine(
                            library.observeIsLiked(id),
                            downloads.observeDownload(id).map { it?.state == DownloadState.COMPLETED && it.filePath != null },
                            sleepTimer,
                        ) { liked, offline, sleep -> Snapshot(true, SessionContract.PublishedState(liked, offline, sleep)) }
                    }
                }
                .distinctUntilChanged()
                .collect { publish(it) }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun onCurrentMediaId(id: String?) {
        currentMediaId.value = id
    }

    fun onSleepTimerChanged(state: SleepTimerState) {
        sleepTimer.value = state
    }

    private fun publish(snapshot: Snapshot) {
        current = snapshot.state
        session.setSessionExtras(SessionContract.encodeExtras(snapshot.state))
        publishButtons(snapshot.hasItem, snapshot.state.liked)
    }

    private fun publishButtons(hasItem: Boolean, liked: Boolean) {
        val key = hasItem to liked
        if (key == lastButtonKey) return
        lastButtonKey = key
        session.setMediaButtonPreferences(listOf(likeButton(context, liked, hasItem)))
    }

    companion object {
        /**
         * Bouton like en slot « overflow » : les boutons précédent / lecture / suivant gardent leur place.
         */
        fun likeButton(context: Context, liked: Boolean, enabled: Boolean = true): CommandButton =
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setSessionCommand(SessionContract.toggleLikeCommand)
                .setDisplayName(context.getString(if (liked) R.string.player_unlike else R.string.player_like))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setEnabled(enabled)
                .build()
    }
}
