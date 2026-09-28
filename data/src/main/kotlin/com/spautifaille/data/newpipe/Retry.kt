package com.spautifaille.data.newpipe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exécute [block] jusqu'à [times] fois (tentative initiale comprise) tant qu'il échoue avec une erreur
 * pour laquelle [shouldRetry] renvoie `true`. Le délai entre deux tentatives vaut [initialDelay] puis
 * est multiplié par [factor] à chaque échec (500 ms, 1 s, 2 s...).
 *
 * Les [CancellationException] ne sont jamais rejouées. La dernière erreur est propagée telle quelle.
 */
suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelay: Duration = 500.milliseconds,
    factor: Double = 2.0,
    shouldRetry: (Throwable) -> Boolean,
    block: suspend (attempt: Int) -> T,
): T {
    require(times >= 1) { "times must be >= 1" }
    var currentDelay = initialDelay
    var attempt = 0
    while (true) {
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            attempt++
            if (attempt >= times || !shouldRetry(e)) throw e
            delay(currentDelay)
            currentDelay *= factor
        }
    }
}
