package com.spautifaille.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlayerEvent
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/**
 * ViewModel racine : thème, messages éphémères (snackbar) et relais de la demande unique de la permission
 * de notifications (déclenchée aussi par les téléchargements et les imports via [NotificationPermissionRequester]).
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    messenger: UiMessenger,
    private val permissionRequester: NotificationPermissionRequester,
) : ViewModel() {

    /** Nul tant que les réglages ne sont pas chargés (évite un flash du mauvais thème). */
    val themeSettings: StateFlow<ThemeSettings?> = settingsRepository.settings
        .map { ThemeSettings(it.themeMode, it.dynamicColor) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Messages à afficher dans la snackbar : bus de l'UI + événements du lecteur. */
    val messages: Flow<UiText> = merge(
        messenger.messages,
        playbackController.events.map(::eventToText),
    )

    /** Émet une fois quand la permission POST_NOTIFICATIONS doit être demandée (lecture, téléchargement ou import). */
    val notificationPermissionRequests: Flow<Unit> = permissionRequester.requests

    init {
        viewModelScope.launch {
            playbackController.state.first { it.isPlaying || it.playWhenReady }
            permissionRequester.requestIfNeeded()
        }
    }

    private fun eventToText(event: PlayerEvent): UiText = when (event) {
        is PlayerEvent.TrackSkipped ->
            UiText.of(R.string.snack_track_skipped, event.track.title, UiText.of(event.error.toMessage()))
        is PlayerEvent.Error -> UiText.of(event.error.toMessage())
        PlayerEvent.SleepTimerFinished -> UiText.of(R.string.snack_sleep_timer_finished)
    }
}
