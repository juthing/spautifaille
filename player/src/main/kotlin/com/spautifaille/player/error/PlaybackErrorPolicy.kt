package com.spautifaille.player.error

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.spautifaille.domain.error.AppError
import com.spautifaille.player.datasource.StreamResolutionException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Ce que le gestionnaire d'erreurs doit faire après une [PlaybackException]. */
sealed interface ErrorAction {
    /** Invalider l'URL en cache, puis re-préparer et reprendre à la position courante. */
    data object ReResolve : ErrorAction

    /** Re-préparer après [delayMs] (attente réseau : voir `PlaybackErrorHandler`). */
    data class Retry(val delayMs: Long) : ErrorAction

    /** Erreur irrécupérable pour ce titre : passer au suivant et prévenir l'utilisateur. */
    data class Skip(val error: AppError) : ErrorAction

    /** Erreur irrécupérable et rien à enchaîner : rester en pause et prévenir l'utilisateur. */
    data class Fail(val error: AppError) : ErrorAction
}

/** Nature d'une erreur de lecture, indépendamment des compteurs de tentatives. */
sealed interface ErrorKind {
    /** Réseau indisponible / timeout / erreur serveur passagère. */
    data object Network : ErrorKind

    /** URL de flux expirée ou refusée (403/410/416) : re-résolution. */
    data object Expired : ErrorKind

    /** Limitation de débit / détection de bot (429). Récupérable plus tard. */
    data object BotDetected : ErrorKind

    /** Fichier téléchargé introuvable : on retombe sur le streaming. */
    data object FileMissing : ErrorKind

    /** Irrécupérable pour ce titre. */
    data class Fatal(val error: AppError) : ErrorKind
}

data class ErrorContext(
    val reResolveAttempts: Int,
    val networkAttempts: Int,
    val hasNext: Boolean,
)

/**
 * Politique de gestion des erreurs de lecture — fonctions pures, sans état.
 * Les exceptions du résolveur arrivent enveloppées (Loader -> ExoPlaybackException) : on parcourt la chaîne de causes.
 */
@OptIn(UnstableApi::class)
object PlaybackErrorPolicy {
    const val MAX_RE_RESOLVE_ATTEMPTS = 2
    const val MAX_NETWORK_ATTEMPTS = 6
    const val MAX_BOT_ATTEMPTS = 3
    private const val MAX_CAUSE_DEPTH = 8
    private const val BACKOFF_BASE_MS = 1_000L
    private const val BACKOFF_MAX_MS = 30_000L

    /** 1 s, 2 s, 4 s, 8 s, 16 s, puis 30 s max. [attempt] compte à partir de 0. */
    fun backoffMs(attempt: Int): Long {
        if (attempt >= 5) return BACKOFF_MAX_MS
        return (BACKOFF_BASE_MS shl attempt.coerceAtLeast(0)).coerceAtMost(BACKOFF_MAX_MS)
    }

    fun causeChain(error: Throwable): List<Throwable> =
        generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

    fun classify(error: PlaybackException): ErrorKind {
        val chain = causeChain(error)

        chain.filterIsInstance<StreamResolutionException>().firstOrNull()?.let { resolution ->
            return when (val app = resolution.appError) {
                AppError.Network -> ErrorKind.Network
                AppError.StreamExpired -> ErrorKind.Expired
                AppError.BotDetected -> ErrorKind.BotDetected
                else -> ErrorKind.Fatal(app)
            }
        }

        chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let { http ->
            return when (http.responseCode) {
                403, 410, 416 -> ErrorKind.Expired
                429 -> ErrorKind.BotDetected
                404 -> ErrorKind.Fatal(AppError.Unavailable)
                in 500..599 -> ErrorKind.Network
                else -> ErrorKind.Fatal(AppError.Unknown("HTTP ${http.responseCode}"))
            }
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> ErrorKind.Network

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> ErrorKind.FileMissing

            PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                if (chain.any { it.isNetworkFailure() }) ErrorKind.Network else ErrorKind.Fatal(AppError.Unknown(error.errorCodeName))

            else -> ErrorKind.Fatal(AppError.Unknown(error.errorCodeName))
        }
    }

    fun decide(error: PlaybackException, context: ErrorContext): ErrorAction {
        fun terminal(appError: AppError): ErrorAction =
            if (context.hasNext) ErrorAction.Skip(appError) else ErrorAction.Fail(appError)

        return when (val kind = classify(error)) {
            ErrorKind.Expired, ErrorKind.FileMissing ->
                if (context.reResolveAttempts < MAX_RE_RESOLVE_ATTEMPTS) ErrorAction.ReResolve
                else terminal(AppError.StreamExpired)

            ErrorKind.Network ->
                if (context.networkAttempts < MAX_NETWORK_ATTEMPTS) ErrorAction.Retry(backoffMs(context.networkAttempts))
                else ErrorAction.Fail(AppError.Network)

            ErrorKind.BotDetected ->
                if (context.networkAttempts < MAX_BOT_ATTEMPTS) ErrorAction.Retry(backoffMs(context.networkAttempts + 2))
                else terminal(AppError.BotDetected)

            is ErrorKind.Fatal -> terminal(kind.error)
        }
    }

    private fun Throwable.isNetworkFailure(): Boolean =
        this is UnknownHostException || this is SocketTimeoutException || this is ConnectException ||
            this is NoRouteToHostException || this is SocketException ||
            (this is IOException && message?.contains("unexpected end of stream", ignoreCase = true) == true)
}
