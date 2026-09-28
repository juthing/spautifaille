package com.spautifaille.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Texte affichable produit hors composable (ViewModel) : chaîne brute ou ressource + arguments.
 * Un argument peut lui-même être un [UiText] (résolu récursivement).
 */
sealed interface UiText {
    data class Plain(val value: String) : UiText
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    fun asString(context: Context): String = when (this) {
        is Plain -> value
        is Resource -> context.getString(
            id,
            *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray(),
        )
    }

    @Composable
    fun asString(): String = asString(LocalContext.current)

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Resource(id, args.toList())
    }
}
