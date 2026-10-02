package com.spautifaille.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.ColorSource
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thème de l'application. [nowPlayingArtworkUrl] = pochette du titre en cours, renseignée seulement avec
 * [ColorSource.NOW_PLAYING] (sinon `null` : un changement de titre ne touche pas le thème).
 */
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.DYNAMIC,
    val nowPlayingArtworkUrl: String? = null,
)

/**
 * ViewModel racine : thème, messages éphémères (snackbar) et relais de la demande unique de la permission
 * de notifications (déclenchée aussi par les téléchargements et les imports via [NotificationPermissionRequester]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    messenger: UiMessenger,
    private val permissionRequester: NotificationPermissionRequester,
) : ViewModel() {

    /** Nul tant que les réglages ne sont pas chargés (évite un flash du mauvais thème). */
    val themeSettings: StateFlow<ThemeSettings?> = settingsRepository.settings
        .map { it.themeMode to it.colorSource }
        .distinctUntilChanged()
        .flatMapLatest { (themeMode, colorSource) -> themeSettingsFlow(themeMode, colorSource) }
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

    private fun themeSettingsFlow(themeMode: ThemeMode, colorSource: ColorSource): Flow<ThemeSettings> =
        if (colorSource == ColorSource.NOW_PLAYING) {
            playbackController.state
                .map { it.currentTrack?.thumbnailUrl }
                .distinctUntilChanged()
                .map { ThemeSettings(themeMode, colorSource, it) }
        } else {
            flowOf(ThemeSettings(themeMode, colorSource))
        }

    private fun eventToText(event: PlayerEvent): UiText = when (event) {
        is PlayerEvent.TrackSkipped ->
            UiText.of(R.string.snack_track_skipped, event.track.title, UiText.of(event.error.toMessage()))
        is PlayerEvent.Error -> UiText.of(event.error.toMessage())
        PlayerEvent.SleepTimerFinished -> UiText.of(R.string.snack_sleep_timer_finished)
    }
}
