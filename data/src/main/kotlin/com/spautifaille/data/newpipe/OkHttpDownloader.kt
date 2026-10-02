package com.spautifaille.data.newpipe

import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.brotli.Brotli
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Portage OkHttp du `DownloaderImpl` de NewPipe.
 *
 * - User-Agent Firefox desktop par défaut ; les en-têtes de la requête NewPipe REMPLACENT ceux par défaut.
 * - Cookies : `youtube_restricted_mode_key` (désactivé par défaut) + cookies reCAPTCHA éventuels.
 * - HTTP 429 -> [ReCaptchaException] ; les autres réponses non 2xx sont RENVOYÉES, jamais levées.
 *
 * Les réponses 2xx de l'endpoint `player` d'InnerTube sont aussi transmises à [PlayerLoudnessRecorder]
 * (niveau sonore pour la normalisation du volume).
 *
 * Le client partagé est dérivé (`newBuilder`) : même pool de connexions et dispatcher, mais timeout de
 * lecture de 30 s et décompression gzip/brotli propres au Downloader.
 */
@Singleton
class OkHttpDownloader @Inject constructor(
    sharedClient: OkHttpClient,
    private val loudnessRecorder: PlayerLoudnessRecorder,
) : Downloader() {

    /** Sans mémorisation du niveau sonore (tests, usages hors injection). */
    constructor(sharedClient: OkHttpClient) : this(sharedClient, PlayerLoudnessRecorder(NoOpLoudnessStore))

    private val client: OkHttpClient = sharedClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(CompressionInterceptor(Brotli, Gzip))
        .build()

    private val cookies = ConcurrentHashMap<String, String>()

    fun getCookie(key: String): String? = cookies[key]

    fun setCookie(key: String, cookie: String) {
        cookies[key] = cookie
    }

    fun removeCookie(key: String) {
        cookies.remove(key)
    }

    /** Active / désactive le mode restreint YouTube (cookie `PREF=f2=8000000`). */
    fun updateYoutubeRestrictedModeCookies(enabled: Boolean) {
        if (enabled) setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY, YOUTUBE_RESTRICTED_MODE_COOKIE)
        else removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
    }

    /** Valeur de l'en-tête `Cookie` pour [url] (chaîne vide si aucun cookie). */
    fun getCookies(url: String): String {
        val youtubeCookie = if (url.contains(YOUTUBE_DOMAIN)) getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY) else null
        return listOfNotNull(youtubeCookie, getCookie(RECAPTCHA_COOKIES_KEY))
            .flatMap { it.split(COOKIE_SEPARATOR) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val dataToSend = request.dataToSend()

        val body = when {
            dataToSend != null -> dataToSend.toRequestBody()
            httpMethod.uppercase() in METHODS_REQUIRING_BODY -> ByteArray(0).toRequestBody()
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .method(httpMethod, body)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        val cookieHeader = getCookies(url)
        if (cookieHeader.isNotEmpty()) builder.addHeader("Cookie", cookieHeader)

        // Les en-têtes fournis par NewPipe remplacent ceux par défaut (User-Agent, Cookie...).
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val responseBody = response.body.string()
            // NewPipeExtractor n'expose pas le niveau sonore de la réponse `player` : on le relève au passage.
            if (response.isSuccessful) loudnessRecorder.onResponse(url, responseBody)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE_KEY = "youtube_restricted_mode_key"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000"
        const val YOUTUBE_DOMAIN = "youtube.com"
        const val RECAPTCHA_COOKIES_KEY = "recaptcha_cookies"

        private val COOKIE_SEPARATOR = Regex("; *")
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
    }
}
