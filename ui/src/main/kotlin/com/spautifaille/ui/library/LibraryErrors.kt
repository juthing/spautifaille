package com.spautifaille.ui.library

import androidx.annotation.StringRes
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.ui.R

/** Message utilisateur (français) pour une [AppError]. Local au lot bibliothèque ; à dédupliquer à l'intégration. */
@StringRes
internal fun AppError.libraryMessage(): Int = when (this) {
    AppError.Unavailable -> R.string.lib_error_unavailable
    AppError.AgeRestricted -> R.string.lib_error_age_restricted
    AppError.GeoBlocked -> R.string.lib_error_geo_blocked
    AppError.PaidContent -> R.string.lib_error_paid_content
    AppError.BotDetected -> R.string.lib_error_bot_detected
    AppError.Network -> R.string.lib_error_network
    AppError.StreamExpired -> R.string.lib_error_stream_expired
    AppError.NoAudioStream -> R.string.lib_error_no_audio_stream
    is AppError.ExtractionBroken -> R.string.lib_error_extraction_broken
    is AppError.Unknown -> R.string.lib_error_unknown
}

/** Convertit n'importe quelle exception en [AppError] (les repositories lèvent normalement [AppException]). */
internal fun Throwable.toLibraryAppError(): AppError =
    (this as? AppException)?.error ?: AppError.Unknown(message)
