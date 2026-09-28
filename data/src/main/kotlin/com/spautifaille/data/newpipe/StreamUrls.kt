package com.spautifaille.data.newpipe

/**
 * Helpers purs autour des URL de flux YouTube (`googlevideo.com`), testables sans NewPipe.
 * Ils fonctionnent aussi sur le contenu d'un manifeste DASH (où `&` est échappé en `&amp;`).
 */
internal object StreamUrls {
    /** Marge de sécurité retranchée à l'expiration annoncée. */
    const val SAFETY_MARGIN_MS = 60_000L

    /** Durée de vie supposée quand l'URL n'annonce pas d'expiration. */
    const val FALLBACK_TTL_MS = 5L * 60 * 60 * 1000

    const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    // `expire=1700000000` en query, ou `/expire/1700000000/` en chemin (URL de manifeste).
    private val EXPIRE_REGEX = Regex("""[?&;/]expire[=/](\d{9,11})""")
    private val CLIENT_REGEX = Regex("""(?:[?&;/])c[=/]([A-Z0-9_]+)""")

    /** Epoch en millisecondes annoncée par le paramètre `expire`, ou `null`. */
    fun parseExpireMs(url: String): Long? =
        EXPIRE_REGEX.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

    /** Instant d'expiration à retenir : `expire` - 60 s, sinon [nowMs] + 5 h. */
    fun expiresAtMs(url: String, nowMs: Long): Long =
        parseExpireMs(url)?.minus(SAFETY_MARGIN_MS) ?: (nowMs + FALLBACK_TTL_MS)

    /** Valeur du paramètre `c` (client YouTube : WEB, VISIONOS, ANDROID...). */
    fun client(url: String): String? = CLIENT_REGEX.find(url)?.groupValues?.get(1)

    fun isVisionOs(url: String): Boolean = client(url) == "VISIONOS"

    fun isWeb(url: String): Boolean = client(url)?.startsWith("WEB") == true

    /**
     * En-têtes obligatoires pour lire [url] :
     * - VisionOS : User-Agent VisionOS (sinon 403) ;
     * - WEB : User-Agent Firefox + Origin/Referer/Sec-Fetch-* comme `YoutubeHttpDataSource` de NewPipe ;
     * - autre : User-Agent par défaut du Downloader.
     */
    fun headersFor(
        url: String,
        defaultUserAgent: String = OkHttpDownloader.USER_AGENT,
        visionOsUserAgent: () -> String,
    ): Map<String, String> = when {
        isVisionOs(url) -> mapOf("User-Agent" to visionOsUserAgent())
        isWeb(url) -> mapOf(
            "User-Agent" to defaultUserAgent,
            "Origin" to YOUTUBE_ORIGIN,
            "Referer" to YOUTUBE_ORIGIN,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
        )
        else -> mapOf("User-Agent" to defaultUserAgent)
    }
}
