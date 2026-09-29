package com.spautifaille.player.datasource

import android.util.Log
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exception d'E/S levée par la chaîne de data sources quand la résolution d'un titre échoue.
 * Étend [IOException] pour traverser `ResolvingDataSource` / le `Loader` d'ExoPlayer ; l'[appError] d'origine reste
 * récupérable en parcourant la chaîne de causes de la `PlaybackException`.
 */
class StreamResolutionException(val appError: AppError, cause: Throwable? = null) :
    IOException("Stream resolution failed: $appError", cause)

/**
 * Résolution des flux avec cache mémoire à durée de vie limitée (jamais persisté : les URL expirent).
 *
 * [resolveBlocking] est appelé depuis le thread de chargement du lecteur (`ResolvingDataSource` a le droit de
 * bloquer) ; [resolve] est la variante suspendante testable.
 */
@Singleton
class StreamResolver internal constructor(
    private val streams: StreamRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long,
) {
    @Inject
    constructor(streams: StreamRepository, settings: SettingsRepository) :
        this(streams, settings, System::currentTimeMillis)

    private class Entry(val stream: ResolvedStream, val quality: AudioQuality, val cachedAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var lastKnownQuality: AudioQuality? = null

    suspend fun resolve(videoId: String): ResolvedStream {
        val quality = settings.current().audioQuality
        lastKnownQuality = quality
        freshEntry(videoId, quality)?.let { return it.stream }
        // Un seul appel réseau par titre à la fois (préchargement + lecture simultanés).
        return locks.computeIfAbsent(videoId) { Mutex() }.withLock {
            freshEntry(videoId, quality)?.let { return@withLock it.stream }
            val stream = try {
                streams.resolveAudio(videoId, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                throw StreamResolutionException(e.error, e)
            } catch (e: IOException) {
                throw StreamResolutionException(AppError.Network, e)
            } catch (e: Exception) {
                Log.w(TAG, "resolveAudio($videoId) failed", e)
                throw StreamResolutionException(AppError.Unknown(e.message), e)
            }
            cache[videoId] = Entry(stream, quality, clock())
            stream
        }
    }

    /** Variante bloquante pour le thread de chargement. Une interruption devient une [java.io.InterruptedIOException]. */
    fun resolveBlocking(videoId: String): ResolvedStream = try {
        runBlocking { resolve(videoId) }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw java.io.InterruptedIOException("Interrupted while resolving $videoId")
    }

    /** Oublie l'URL mise en cache (403/410 : elle a expiré ou a été refusée). */
    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    fun invalidateAll() = cache.clear()

    /**
     * Clé de cache disque d'un titre : stable, mais séparée par qualité audio pour ne jamais mélanger deux
     * encodages différents dans les mêmes octets.
     */
    fun cacheKey(videoId: String): String {
        val quality = lastKnownQuality ?: runBlocking { settings.current().audioQuality }.also { lastKnownQuality = it }
        return "$videoId#${quality.name}"
    }

    private fun freshEntry(videoId: String, quality: AudioQuality): Entry? {
        val entry = cache[videoId] ?: return null
        val now = clock()
        val fresh = entry.quality == quality &&
            now - entry.cachedAtMs < MAX_TTL_MS &&
            now < entry.stream.expiresAtMs - EXPIRY_MARGIN_MS
        if (!fresh) cache.remove(videoId, entry)
        return entry.takeIf { fresh }
    }

    companion object {
        private const val TAG = "StreamResolver"

        /** Durée de vie maximale d'une entrée, même si `expiresAtMs` est plus lointain. */
        const val MAX_TTL_MS = 30 * 60_000L

        /** Marge de sécurité avant `expiresAtMs`. */
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
