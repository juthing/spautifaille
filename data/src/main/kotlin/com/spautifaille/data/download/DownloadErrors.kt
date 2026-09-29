package com.spautifaille.data.download

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import java.io.IOException

/**
 * Messages français stockés dans `downloads.error` (affichés tels quels par l'écran Téléchargements).
 * Le module `:data` n'a pas de ressources : les libellés vivent ici.
 */
internal object DownloadMessages {
    const val NOT_DOWNLOADABLE = "Format non téléchargeable"
    const val NO_SPACE = "Espace de stockage insuffisant"
    const val FILE_MISSING = "Fichier introuvable"
    const val INCOMPLETE = "Téléchargement incomplet"

    fun forError(error: AppError): String = when (error) {
        AppError.Unavailable -> "Contenu indisponible"
        AppError.AgeRestricted -> "Contenu soumis à une restriction d'âge"
        AppError.GeoBlocked -> "Indisponible dans votre pays"
        AppError.PaidContent -> "Contenu payant ou réservé aux abonnés"
        AppError.BotDetected -> "YouTube limite temporairement les requêtes"
        AppError.Network -> "Connexion impossible"
        AppError.StreamExpired -> "Le lien de téléchargement a expiré"
        AppError.NoAudioStream -> "Aucun flux audio disponible"
        is AppError.ExtractionBroken -> "Extraction impossible : mettez l'application à jour"
        is AppError.Unknown -> "Erreur inattendue"
    }
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
