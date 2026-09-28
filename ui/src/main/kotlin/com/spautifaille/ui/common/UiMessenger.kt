package com.spautifaille.ui.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bus de messages éphémères (snackbar) partagé par toute l'UI. Les ViewModels y publient des [UiText] ;
 * le shell de l'application les affiche (y compris au-dessus du lecteur plein écran).
 */
@Singleton
class UiMessenger @Inject constructor() {
    private val _messages = MutableSharedFlow<UiText>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: Flow<UiText> get() = _messages

    fun show(text: UiText) {
        _messages.tryEmit(text)
    }

    fun show(message: String) = show(UiText.Plain(message))
}
