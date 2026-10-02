package com.spautifaille.domain.model

/**
 * Réécriture des URL de miniatures pour l'affichage en grand. Les URL stockées (`Track.thumbnailUrl`) restent
 * celles choisies par `:data` (<= ~720 px, adaptées aux listes) ; ce helper pur en dérive une version haute
 * résolution carrée pour le lecteur plein écran. [landscapeCandidates] en dérive les sources d'une affiche
 * paysage 16:9 (carte média de l'écran de verrouillage, notification), recadrée au centre par `:player`.
 */
object ArtworkUrls {
    private const val MIN_SIZE_PX = 64
    private const val MAX_SIZE_PX = 1_200

    /** En dessous de cette taille demandée, la vignette d'origine suffit (pas de réécriture YouTube). */
    private const val YTIMG_MIN_SIZE_PX = 480
    private const val YTIMG_DEFAULT_HOST = "https://i.ytimg.com"

    private val googleHost = Regex("""^https?://([a-z0-9-]+\.)*(googleusercontent\.com|ggpht\.com)/""", RegexOption.IGNORE_CASE)
    private val ytimgVideo = Regex(
        """^(https?://(?:i|i9|img)\.ytimg\.com)/(vi|vi_webp)/([A-Za-z0-9_-]{11})/[^/?#]+$""",
        RegexOption.IGNORE_CASE,
    )
    private val videoIdPattern = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val sizeToken = Regex("""(^|-)(s|w|h)\d+""")

    /**
     * Meilleure URL carrée pour un affichage d'environ [sizePx] pixels de côté, sans repli.
     * Équivaut au premier élément de [squareCandidates].
     */
    fun square(url: String?, sizePx: Int): String? = squareCandidates(url, sizePx).firstOrNull()

    /**
     * URL à essayer dans l'ordre pour un affichage carré de [sizePx] px : la version haute résolution d'abord
     * (si l'URL est reconnue), l'URL d'origine toujours en dernier recours (si la première n'existe pas, par
     * exemple `maxresdefault` absent). Liste vide si [url] est nul ou vide.
     */
    fun squareCandidates(url: String?, sizePx: Int): List<String> {
        if (url.isNullOrBlank()) return emptyList()
        val size = sizePx.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        val upgraded = upgradeGoogle(url, size) ?: upgradeYtimg(url, size)
        return if (upgraded != null && upgraded != url) listOf(upgraded, url) else listOf(url)
    }

    /**
     * Sources, à essayer dans l'ordre, pour fabriquer une affiche paysage 16:9 du titre ([videoId] facultatif : à
     * défaut il est lu dans l'URL d'une miniature YouTube). Les images renvoyées ne sont PAS toutes en 16:9 : le
     * consommateur les recadre au centre en 16:9 plein cadre (miniatures 4:3 à bandes noires retirées, pochettes carrées
     * dont le haut et le bas sont coupés).
     *
     * - pochette `googleusercontent.com` / `ggpht.com` (titres YouTube Music) : la pochette carrée haute résolution
     *   (1200 px), recadrée au centre, puis l'URL d'origine ;
     * - miniature YouTube (ou titre sans miniature mais avec [videoId]) : `maxresdefault` (1280x720, peut être
     *   absente), `sddefault` (640x480, 4:3 avec bandes), `hqdefault` (480x360, 4:3 avec bandes, toujours présente),
     *   puis l'URL d'origine ;
     * - toute autre URL : elle seule. Liste vide si rien n'est exploitable.
     */
    fun landscapeCandidates(videoId: String?, thumbnailUrl: String?): List<String> {
        val url = thumbnailUrl?.takeIf { it.isNotBlank() }
        if (url != null && googleHost.containsMatchIn(url)) return squareCandidates(url, MAX_SIZE_PX)
        val ytimg = url?.let { ytimgVideo.matchEntire(it.substringBefore('?').substringBefore('#')) }
        val id = videoId?.takeIf { videoIdPattern.matches(it) } ?: ytimg?.groupValues?.get(3)
        if (id == null) return listOfNotNull(url)
        val host = ytimg?.groupValues?.get(1) ?: YTIMG_DEFAULT_HOST
        return listOf("maxresdefault", "sddefault", "hqdefault")
            .map { "$host/vi/$id/$it.jpg" }
            .let { if (url != null && url !in it) it + url else it }
    }

    /**
     * `https://lh3.googleusercontent.com/<id>=w120-h120-l90-rj` (ou `=s88-c`) : on remplace les paramètres
     * de taille par `=w<size>-h<size>-l90-rj`. Sans paramètre de taille reconnu, l'URL est laissée telle quelle.
     */
    private fun upgradeGoogle(url: String, size: Int): String? {
        if (!googleHost.containsMatchIn(url)) return null
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val lastSlash = withoutQuery.lastIndexOf('/')
        val eq = withoutQuery.indexOf('=', startIndex = lastSlash + 1)
        if (eq < 0) return null
        val params = withoutQuery.substring(eq + 1)
        if (!sizeToken.containsMatchIn(params)) return null
        return withoutQuery.substring(0, eq + 1) + "w$size-h$size-l90-rj"
    }

    /**
     * `https://i.ytimg.com/vi/<id>/<nom>.jpg[?sqp=...]` : `maxresdefault.jpg` (1280x720, sans bandes noires,
     * recadrable en carré). Les paramètres signés (`sqp`, `rs`) sont propres à la vignette d'origine et
     * retirés ; si `maxresdefault` n'existe pas (404), l'appelant retombe sur l'URL d'origine.
     */
    private fun upgradeYtimg(url: String, size: Int): String? {
        if (size < YTIMG_MIN_SIZE_PX) return null
        val match = ytimgVideo.matchEntire(url.substringBefore('?').substringBefore('#')) ?: return null
        val (host, kind, id) = match.destructured
        if (kind.equals("vi_webp", ignoreCase = true)) return "$host/vi_webp/$id/maxresdefault.webp"
        return "$host/vi/$id/maxresdefault.jpg"
    }
}
