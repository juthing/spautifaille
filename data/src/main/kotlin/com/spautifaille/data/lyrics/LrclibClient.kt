package com.spautifaille.data.lyrics

import com.spautifaille.data.newpipe.HttpResponseException
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.lyrics.LyricsCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fiche renvoyée par LRCLIB (`/api/get`, `/api/search`). Champs inconnus ignorés. */
@Serializable
internal data class LrclibTrack(
    val trackName: String? = null,
    val name: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
) {
    fun toCandidate() = LyricsCandidate(
        trackName = trackName ?: name.orEmpty(),
        artistName = artistName.orEmpty(),
        durationSec = duration,
        instrumental = instrumental,
        syncedLyrics = syncedLyrics,
        plainLyrics = plainLyrics,
    )
}

/**
 * Client de l'API LRCLIB (https://lrclib.net/docs), sans clé. Envoie un User-Agent identifiant l'application,
 * comme le demande le service.
 *
 * Erreurs : 404 de `/api/get` -> `null` ; réseau, HTTP 429 et 5xx -> `AppException(AppError.Network)` ;
 * autre statut ou JSON illisible -> `AppException(AppError.Unknown)`.
 */
class LrclibClient(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val userAgent: String = USER_AGENT,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Correspondance exacte (`/api/get`) ; la durée est tolérée à ±2 s par le service. `null` si introuvable. */
    suspend fun get(trackName: String, artistName: String, albumName: String?, durationSec: Int?): LyricsCandidate? {
        val url = baseUrl.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .apply {
                albumName?.let { addQueryParameter("album_name", it) }
                durationSec?.let { addQueryParameter("duration", it.toString()) }
            }
            .build()
        val body = fetchBody(url, notFoundIsEmpty = true) ?: return null
        return decode<LrclibTrack>(body).toCandidate()
    }

    /** Recherche (`/api/search`) par `track_name` + `artist_name`, ou par texte libre [q]. */
    suspend fun search(trackName: String? = null, artistName: String? = null, q: String? = null): List<LyricsCandidate> {
        val url = baseUrl.newBuilder().addPathSegments("api/search")
            .apply {
                trackName?.let { addQueryParameter("track_name", it) }
                artistName?.let { addQueryParameter("artist_name", it) }
                q?.let { addQueryParameter("q", it) }
            }
            .build()
        val body = fetchBody(url, notFoundIsEmpty = true) ?: return emptyList()
        return decode<List<LrclibTrack>>(body).map { it.toCandidate() }
    }

    private suspend fun fetchBody(url: HttpUrl, notFoundIsEmpty: Boolean): String? = withContext(ioDispatcher) {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw AppException(AppError.Network, e)
        }
        response.use {
            when {
                it.code == 404 && notFoundIsEmpty -> null
                it.isSuccessful -> try {
                    it.body.string()
                } catch (e: IOException) {
                    throw AppException(AppError.Network, e)
                }
                it.code == 429 || it.code >= 500 -> throw AppException(AppError.Network, HttpResponseException(it.code))
                else -> throw AppException(AppError.Unknown("LRCLIB : HTTP ${it.code}"))
            }
        }
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw AppException(AppError.Unknown("LRCLIB : réponse illisible"), e)
    } catch (e: IllegalArgumentException) {
        throw AppException(AppError.Unknown("LRCLIB : réponse illisible"), e)
    }

    private suspend fun Call.await(): Response {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, _, _ -> response.close() }
                    }
                },
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net/"
        const val USER_AGENT = "Spautifaille/1.0 (https://github.com/juthing/spautifaille)"
    }
}
