package com.spautifaille.data.download

import android.content.Context
import com.spautifaille.data.R
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import java.io.IOException

/**
 * Messages (français, depuis les ressources `data_download_error_*`) stockés dans `downloads.error` et affichés tels
 * quels par l'écran Téléchargements : le texte est résolu au moment de l'écriture en base.
 */
internal class DownloadMessages(private val context: Context) {
    val notDownloadable: String get() = context.getString(R.string.data_download_error_not_downloadable)
    val noSpace: String get() = context.getString(R.string.data_download_error_no_space)
    val fileMissing: String get() = context.getString(R.string.data_download_error_file_missing)

    fun forError(error: AppError): String = context.getString(
        when (error) {
            AppError.Unavailable -> R.string.data_download_error_unavailable
            AppError.AgeRestricted -> R.string.data_download_error_age_restricted
            AppError.GeoBlocked -> R.string.data_download_error_geo_blocked
            AppError.PaidContent -> R.string.data_download_error_paid
            AppError.BotDetected -> R.string.data_download_error_bot
            AppError.Network -> R.string.data_download_error_network
            AppError.StreamExpired -> R.string.data_download_error_stream_expired
            AppError.NoAudioStream -> R.string.data_download_error_no_audio
            is AppError.ExtractionBroken -> R.string.data_download_error_extraction
            is AppError.Unknown -> R.string.data_download_error_unknown
        },
    )
}

/** Erreurs qui ne disparaîtront pas en réessayant : le téléchargement est abandonné (FAILED). */
internal fun AppError.isPermanent(): Boolean = this is AppError.Unavailable ||
    this is AppError.AgeRestricted ||
    this is AppError.GeoBlocked ||
    this is AppError.PaidContent ||
    this is AppError.NoAudioStream

/** Convertit n'importe quelle exception de téléchargement en [AppError]. */
internal fun Throwable.toDownloadError(): AppError = when (this) {
    is AppException -> error
    is HttpStatusException -> when (code) {
        403 -> AppError.StreamExpired
        429 -> AppError.BotDetected
        else -> AppError.Network
    }
    is IOException -> AppError.Network
    else -> AppError.Unknown(message)
}

/** Disque plein (`ENOSPC`) : inutile de réessayer tant que l'utilisateur n'a pas libéré de la place. */
internal fun Throwable.isOutOfSpace(): Boolean =
    this is IOException && (message?.contains("ENOSPC") == true || message?.contains("No space left", ignoreCase = true) == true)
