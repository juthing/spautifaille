package com.spautifaille.ui.common

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

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
 * Point unique de déclenchement de la demande de permission POST_NOTIFICATIONS (Android 13+).
 * Les ViewModels appellent [requestIfNeeded] au moment où une notification devient utile (première lecture,
 * premier téléchargement, premier import) ; la racine de l'UI collecte [requests] et lance la demande système.
 * La demande n'est émise qu'une seule fois : le refus est mémorisé et n'est pas réitéré.
 */
@Singleton
class NotificationPermissionRequester internal constructor(
    private val prefs: UiPrefs,
    private val sdkInt: Int,
) {
    @Inject
    constructor(prefs: UiPrefs) : this(prefs, Build.VERSION.SDK_INT)

    private val _requests = Channel<Unit>(Channel.CONFLATED)

    /** Émet une fois au plus, quand la permission doit être demandée. */
    val requests: Flow<Unit> = _requests.receiveAsFlow()

    fun requestIfNeeded() {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return
        synchronized(this) {
            if (prefs.notificationPermissionAsked) return
            prefs.notificationPermissionAsked = true
        }
        _requests.trySend(Unit)
    }
}
