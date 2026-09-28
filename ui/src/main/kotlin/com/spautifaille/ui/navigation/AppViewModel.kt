package com.spautifaille.ui.navigation

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlayerEvent
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** Petites préférences propres à l'UI (hors réglages utilisateur). */
@Singleton
class UiPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("spautifaille_ui", Context.MODE_PRIVATE)

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()
        }

    private companion object {
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}

/**
 * ViewModel racine : thème, messages éphémères (snackbar) et demande unique de la permission de notifications.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    messenger: UiMessenger,
    private val prefs: UiPrefs,
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

    private val permissionRequests = Channel<Unit>(Channel.CONFLATED)

    /** Émet une fois, au premier lancement d'une lecture, quand la permission POST_NOTIFICATIONS doit être demandée. */
    val notificationPermissionRequests: Flow<Unit> = permissionRequests.receiveAsFlow()

    init {
        viewModelScope.launch {
            playbackController.state.first { it.isPlaying || it.playWhenReady }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.notificationPermissionAsked) {
                prefs.notificationPermissionAsked = true
                permissionRequests.send(Unit)
            }
        }
    }

    private fun eventToText(event: PlayerEvent): UiText = when (event) {
        is PlayerEvent.TrackSkipped ->
            UiText.of(R.string.snack_track_skipped, event.track.title, UiText.of(event.error.toMessage()))
        is PlayerEvent.Error -> UiText.of(event.error.toMessage())
        PlayerEvent.SleepTimerFinished -> UiText.of(R.string.snack_sleep_timer_finished)
    }
}
