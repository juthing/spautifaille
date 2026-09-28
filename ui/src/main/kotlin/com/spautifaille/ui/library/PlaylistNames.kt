package com.spautifaille.ui.library

/** Erreur de validation d'un nom de playlist. */
enum class PlaylistNameError { BLANK, TOO_LONG }

/** Règles de nommage des playlists : non vide après `trim()`, 100 caractères max. */
object PlaylistNameValidator {
    const val MAX_LENGTH = 100

    fun normalize(name: String): String = name.trim()

    /** `null` si le nom (une fois normalisé) est valide. */
    fun validate(name: String): PlaylistNameError? {
        val normalized = normalize(name)
        return when {
            normalized.isEmpty() -> PlaylistNameError.BLANK
            normalized.length > MAX_LENGTH -> PlaylistNameError.TOO_LONG
            else -> null
        }
    }
}
