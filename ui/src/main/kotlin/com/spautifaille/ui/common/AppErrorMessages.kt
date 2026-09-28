package com.spautifaille.ui.common

import androidx.annotation.StringRes
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.ui.R

/** Message utilisateur (français) associé à une [AppError]. `when` exhaustif : un nouveau cas ne compile pas sans message. */
@StringRes
fun AppError.toMessage(): Int = when (this) {
    AppError.Unavailable -> R.string.apperror_unavailable
    AppError.AgeRestricted -> R.string.apperror_age_restricted
    AppError.GeoBlocked -> R.string.apperror_geo_blocked
    AppError.PaidContent -> R.string.apperror_paid_content
    AppError.BotDetected -> R.string.apperror_bot_detected
    AppError.Network -> R.string.apperror_network
    AppError.StreamExpired -> R.string.apperror_stream_expired
    AppError.NoAudioStream -> R.string.apperror_no_audio_stream
    is AppError.ExtractionBroken -> R.string.apperror_extraction_broken
    is AppError.Unknown -> R.string.apperror_unknown
}

/** Extrait l'[AppError] d'une exception (les repositories lèvent [AppException]). */
fun Throwable.toAppError(): AppError =
    (this as? AppException)?.error ?: AppError.Unknown(message)
