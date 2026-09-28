package com.spautifaille.data.newpipe

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NewPipeErrorMapperTest {

    private fun error(t: Throwable) = NewPipeErrorMapper.map(t).error

    @Test fun ageRestricted() = assertEquals(AppError.AgeRestricted, error(AgeRestrictedContentException("age")))

    @Test fun geoBlocked() = assertEquals(AppError.GeoBlocked, error(GeographicRestrictionException("geo")))

    @Test fun paidContent() {
        assertEquals(AppError.PaidContent, error(PaidContentException("paid")))
        assertEquals(AppError.PaidContent, error(YoutubeMusicPremiumContentException()))
    }

    @Test fun otherContentNotAvailableIsUnavailable() {
        assertEquals(AppError.Unavailable, error(PrivateContentException("private")))
        assertEquals(AppError.Unavailable, error(AccountTerminatedException("terminated")))
        assertEquals(AppError.Unavailable, error(ContentNotAvailableException("gone")))
    }

    @Test fun botDetected() {
        assertEquals(AppError.BotDetected, error(ReCaptchaException("captcha", "https://youtube.com")))
        assertEquals(AppError.BotDetected, error(SignInConfirmNotBotException("sign in")))
    }

    @Test fun networkErrors() {
        assertEquals(AppError.Network, error(IOException("io")))
        assertEquals(AppError.Network, error(SocketTimeoutException("timeout")))
        assertEquals(AppError.Network, error(UnknownHostException("host")))
    }

    @Test fun extractionWrappingIoExceptionIsNetwork() {
        assertEquals(AppError.Network, error(ExtractionException("wrapped", IOException("boom"))))
    }

    @Test fun http403IsStreamExpired() {
        assertEquals(AppError.StreamExpired, error(HttpResponseException(403)))
    }

    @Test fun otherHttpCodes() {
        assertEquals(AppError.BotDetected, error(HttpResponseException(429)))
        assertEquals(AppError.Network, error(HttpResponseException(503)))
    }

    @Test fun parsingAndExtractionAreExtractionBroken() {
        assertEquals(AppError.ExtractionBroken("bad json"), error(ParsingException("bad json")))
        assertEquals(AppError.ExtractionBroken("oops"), error(ExtractionException("oops")))
    }

    @Test fun unknownFallback() {
        assertEquals(AppError.Unknown("weird"), error(IllegalStateException("weird")))
    }

    @Test fun appExceptionIsPassedThrough() {
        val original = AppException(AppError.NoAudioStream)
        assertSame(original, NewPipeErrorMapper.map(original))
    }

    @Test fun causeIsPreserved() {
        val cause = IOException("io")
        assertSame(cause, NewPipeErrorMapper.map(cause).cause)
    }
}
