package com.spautifaille.data.newpipe

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException

/** Réponse HTTP inattendue (ex. 403 sur une URL de flux expirée). */
class HttpResponseException(val responseCode: Int, message: String? = null) :
    IOException(message ?: "HTTP $responseCode")

/** Convertit les exceptions NewPipe / réseau en [AppException]. */
object NewPipeErrorMapper {

    fun map(t: Throwable): AppException {
        if (t is AppException) return t
        return AppException(mapToError(t), t)
    }

    fun mapToError(t: Throwable): AppError = when (t) {
        is AppException -> t.error
        is ReCaptchaException, is SignInConfirmNotBotException -> AppError.BotDetected
        is AgeRestrictedContentException -> AppError.AgeRestricted
        is GeographicRestrictionException -> AppError.GeoBlocked
        is PaidContentException, is YoutubeMusicPremiumContentException -> AppError.PaidContent
        // PrivateContent, AccountTerminated, SoundCloudGoPlus, UnsupportedContentInCountry...
        is ContentNotAvailableException -> AppError.Unavailable
        is HttpResponseException -> when (t.responseCode) {
            403 -> AppError.StreamExpired
            429 -> AppError.BotDetected
            else -> AppError.Network
        }
        // IOException couvre aussi SocketTimeoutException et UnknownHostException.
        is IOException -> AppError.Network
        is ExtractionException ->
            if (t.hasIoCause()) AppError.Network else AppError.ExtractionBroken(t.message)
        else -> if (t.hasIoCause()) AppError.Network else AppError.Unknown(t.message ?: t.javaClass.simpleName)
    }

    private fun Throwable.hasIoCause(): Boolean {
        var current: Throwable? = cause
        var depth = 0
        while (current != null && depth < 8) {
            if (current is IOException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
