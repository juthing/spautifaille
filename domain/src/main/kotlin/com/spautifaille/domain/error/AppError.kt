package com.spautifaille.domain.error

/** Erreurs métier explicites. Les messages lisibles sont produits par la couche UI. */
sealed interface AppError {
    /** Vidéo supprimée, privée ou indisponible. Irrécupérable. */
    data object Unavailable : AppError
    data object AgeRestricted : AppError
    data object GeoBlocked : AppError
    /** Contenu payant, membres, YouTube Music Premium. */
    data object PaidContent : AppError
    /** YouTube demande un reCAPTCHA / confirmation « pas un robot » (throttling IP). Récupérable plus tard. */
    data object BotDetected : AppError
    /** Pas de réseau / timeout. Récupérable. */
    data object Network : AppError
    /** URL de flux expirée ou refusée (HTTP 403). Récupérable par re-résolution. */
    data object StreamExpired : AppError
    /** Aucun flux audio exploitable. */
    data object NoAudioStream : AppError
    /** L'extraction a échoué (YouTube a changé) : il faut probablement mettre à jour NewPipeExtractor. */
    data class ExtractionBroken(val detail: String?) : AppError
    data class Unknown(val detail: String?) : AppError

    val isRecoverable: Boolean
        get() = this is Network || this is StreamExpired || this is BotDetected
}

class AppException(val error: AppError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
