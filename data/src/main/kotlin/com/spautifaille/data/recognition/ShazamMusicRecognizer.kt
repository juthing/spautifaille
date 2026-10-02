/*
 * Requête et parsing inspirés de `src/core/fingerprinting/communication.rs` et `src/core/http_task.rs` de SongRec.
 *
 * SongRec - Copyright (C) marin-m et contributeurs - https://github.com/marin-m/SongRec
 * Licence GPL-3.0 ou ultérieure, compatible avec la licence GPL-3.0 de Spautifaille.
 */
package com.spautifaille.data.recognition

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.recognition.AudioCapture
import com.spautifaille.domain.recognition.MusicRecognizer
import com.spautifaille.domain.recognition.RecognizedTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconnaissance via l'endpoint **non officiel** de découverte de Shazam (le même que celui utilisé par SongRec).
 * Il peut changer ou disparaître sans préavis : toute réponse inattendue est traitée comme une indisponibilité du
 * service, jamais comme un crash.
 *
 * @param endpoint URL de base sans slash final, ex. `https://amp.shazam.com/discovery/v5/en/US/android/-/tag`.
 */
class ShazamMusicRecognizer(
    client: OkHttpClient,
    private val defaultDispatcher: CoroutineDispatcher,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZoneId: () -> String = { TimeZone.getDefault().id },
) : MusicRecognizer {

    // Comme SongRec (`set_force_http1`) : HTTP/1.1 uniquement. Le pool de connexions du client partagé est réutilisé.
    private val http: OkHttpClient = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

    override suspend fun recognize(pcm: ShortArray, sampleRate: Int): RecognizedTrack? {
        require(sampleRate == AudioCapture.SAMPLE_RATE) { "Seul le 16 kHz est supporté (reçu $sampleRate Hz)" }
        val signature = withContext(defaultDispatcher) { SignatureGenerator.generate(pcm) }
        // Aucun pic détecté (silence) : inutile d'interroger le service.
        if (signature.totalPeaks == 0) return null
        val body = buildRequestBody(signature)
        val request = Request.Builder()
            .url(buildUrl())
            .header("User-Agent", USER_AGENT)
            .header("Content-Language", "en_US")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseText = http.newCall(request).await().use { response ->
            when {
                response.code == 429 -> throw AppException(AppError.RecognitionUnavailable)
                !response.isSuccessful -> throw AppException(AppError.RecognitionUnavailable)
                else -> response.body.string()
            }
        }
        return withContext(defaultDispatcher) { parseResponse(responseText) }
    }

    private fun buildUrl(): String {
        // Identifiants aléatoires à usage unique (aucun identifiant d'appareil n'est envoyé).
        val uuid1 = UUID.randomUUID().toString().uppercase(Locale.ROOT)
        val uuid2 = UUID.randomUUID().toString()
        return "$endpoint/$uuid1/$uuid2?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
    }

    private fun buildRequestBody(signature: DecodedSignature): String {
        // `timestamp as u32` côté SongRec : millisecondes tronquées sur 32 bits.
        val timestamp = clock() and 0xFFFFFFFFL
        return buildJsonObject {
            // Position fictive fixe, comme SongRec : la vraie position de l'utilisateur n'est jamais envoyée.
            putJsonObject("geolocation") {
                put("altitude", 300)
                put("latitude", 45)
                put("longitude", 2)
            }
            putJsonObject("signature") {
                put("samplems", signature.sampleMs)
                put("timestamp", timestamp)
                put("uri", signature.encodeToUri())
            }
            put("timestamp", timestamp)
            put("timezone", timeZoneId())
        }.toString()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 7 Build/UQ1A.240205.002)"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Extrait le titre de la réponse JSON de Shazam. `null` quand il n'y a pas de correspondance (objet `track`
         * absent) ; lève [AppException] (`RecognitionUnavailable`) si la réponse n'est pas du JSON.
         */
        internal fun parseResponse(text: String): RecognizedTrack? {
            val root = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw AppException(AppError.RecognitionUnavailable)
            val track = root["track"] as? JsonObject ?: return null
            val title = track.string("title") ?: return null
            val artist = track.string("subtitle") ?: return null

            var album: String? = null
            var released: String? = null
            (track["sections"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("type") == "SONG" && it["metadata"] is JsonArray }
                ?.let { section ->
                    for (item in section["metadata"] as JsonArray) {
                        val meta = item as? JsonObject ?: continue
                        when (meta.string("title")) {
                            "Album" -> album = meta.string("text")
                            "Released" -> released = meta.string("text")
                        }
                    }
                }
            return RecognizedTrack(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = (track["images"] as? JsonObject)?.string("coverart"),
                releaseYear = released,
                genre = (track["genres"] as? JsonObject)?.string("primary"),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/** Exécute l'appel sans bloquer de thread ; annuler la coroutine annule l'appel. Les erreurs d'E/S deviennent `Network`. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(AppException(AppError.Network, e))
        }
    })
    cont.invokeOnCancellation { cancel() }
}
