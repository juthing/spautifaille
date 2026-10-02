package com.spautifaille.data.youtube.api

import com.spautifaille.data.youtube.session.YouTubeSessionStore
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.youtube.YouTubeCredentials
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client InnerTube authentifié de YouTube Music (`WEB_REMIX`), par cookies de session.
 *
 * En-têtes et corps repris de ytmusicapi (`helpers.py` : contexte, `X-Goog-AuthUser`, `Origin`, SAPISIDHASH) et de
 * Metrolist / InnerTubeX (`X-YouTube-Client-*`, `Referer`, `onBehalfOfUser` = `DATASYNC_ID`).
 *
 * Erreurs : HTTP 401/403 → `AppError.YouTubeAuthRequired` (et le compte passe en « reconnexion nécessaire ») ;
 * 429 → `BotDetected` ; 5xx / réseau → `Network` ; réponse inattendue → `YouTubeSyncFailed(endpoint, détail)`.
 * Les cookies ne sont jamais journalisés ni inclus dans un message d'erreur.
 */
@Singleton
internal class InnerTubeClient internal constructor(
    private val http: OkHttpClient,
    private val sessions: YouTubeSessionStore,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val baseUrl: String,
) {
    @Inject
    constructor(
        http: OkHttpClient,
        sessions: YouTubeSessionStore,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(http, sessions, io, System::currentTimeMillis, DEFAULT_BASE_URL)

    /**
     * POST [endpoint] (ex. `browse`, `like/like`) avec [body] enrichi du contexte client. [credentials] : identifiants
     * à utiliser à la place de ceux stockés (validation d'une connexion en cours).
     */
    suspend fun post(
        endpoint: String,
        body: JsonObject = JsonObject(emptyMap()),
        credentials: YouTubeCredentials? = null,
    ): JsonObject {
        val explicit = credentials != null
        val creds = credentials ?: sessions.credentials() ?: throw AppException(AppError.YouTubeAuthRequired)
        val sapisid = SapisidHash.sapisidFromCookie(creds.cookie)
            ?: throw AppException(AppError.YouTubeAuthRequired)

        val nowMs = clock()
        val request = Request.Builder()
            .url("$baseUrl$endpoint?prettyPrint=false")
            .post(bodyWithContext(body, creds, nowMs).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", clientVersion(nowMs))
            .header("X-Goog-AuthUser", creds.authUser.filter(Char::isDigit).ifBlank { "0" })
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Cookie", cookieHeader(creds.cookie))
            .header("Authorization", SapisidHash.authorization(sapisid, ORIGIN, nowMs / 1000))
            .apply { creds.visitorData?.takeIf { it.isNotBlank() }?.let { header("X-Goog-Visitor-Id", it) } }
            .build()

        val text = try {
            withContext(io) {
                http.newCall(request).execute().use { response ->
                    val payload = response.body.string()
                    if (response.isSuccessful) return@use payload
                    throw mapHttpError(endpoint, response.code, payload)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            if (!explicit && e.error == AppError.YouTubeAuthRequired) sessions.markReauthRequired()
            throw e
        } catch (e: IOException) {
            throw AppException(AppError.Network, e)
        }

        val root = try {
            JSON.parseToJsonElement(text)
        } catch (e: Exception) {
            throw AppException(AppError.YouTubeSyncFailed(endpoint, "réponse non JSON"), e)
        }
        val obj = root as? JsonObject
            ?: throw AppException(AppError.YouTubeSyncFailed(endpoint, "la réponse n'est pas un objet JSON"))
        // Certaines erreurs arrivent en HTTP 200 avec un objet `error`.
        obj.at("error")?.let { error ->
            val code = error.at("code").let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            val message = error.at("message").string()
            throw mapHttpError(endpoint, code ?: 500, message ?: "erreur sans message")
        }
        return obj
    }

    private fun bodyWithContext(body: JsonObject, creds: YouTubeCredentials, nowMs: Long): JsonObject = buildJsonObject {
        body.forEach { (key, value) -> put(key, value) }
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", CLIENT_NAME)
                put("clientVersion", clientVersion(nowMs))
                put("hl", "en")
                creds.visitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
            })
            put("user", buildJsonObject {
                put("lockedSafetyMode", false)
                creds.dataSyncId?.substringBefore("||")?.takeIf { it.isNotBlank() }?.let { put("onBehalfOfUser", it) }
            })
        })
    }

    private fun mapHttpError(endpoint: String, code: Int, payload: String): AppException {
        val detail = errorMessage(payload)
        return when (code) {
            401, 403 -> AppException(AppError.YouTubeAuthRequired)
            429 -> AppException(AppError.BotDetected)
            in 500..599 -> AppException(AppError.Network)
            else -> AppException(AppError.YouTubeSyncFailed(endpoint, "HTTP $code${detail?.let { " : $it" }.orEmpty()}"))
        }
    }

    /** Message d'erreur InnerTube (`error.message`) ou texte brut non HTML, tronqué ; jamais le corps complet. */
    private fun errorMessage(payload: String): String? {
        val fromJson = runCatching { JSON.parseToJsonElement(payload).at("error", "message").string() }.getOrNull()
        return (fromJson ?: payload.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") })?.take(200)
    }

    /** `SOCS=CAI` évite l'écran de consentement européen sur les requêtes sans navigateur (valeur reprise de yt-dlp / ytmusicapi). */
    private fun cookieHeader(cookie: String): String =
        if (cookie.contains("SOCS=")) cookie else "$cookie; SOCS=CAI"

    private fun clientVersion(nowMs: Long): String =
        "1." + DATE_FORMAT.format(Instant.ofEpochMilli(nowMs)) + ".01.00"

    companion object {
        const val DEFAULT_BASE_URL = "https://music.youtube.com/youtubei/v1/"
        const val ORIGIN = "https://music.youtube.com"
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_ID = "67"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    }
}

