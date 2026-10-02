package com.spautifaille.domain.model

/**
 * Réécriture des URL de miniatures pour l'affichage en grand. Les URL stockées (`Track.thumbnailUrl`) restent
 * celles choisies par `:data` (<= ~720 px, adaptées aux listes) ; ce helper pur en dérive une version haute
 * résolution carrée pour le lecteur plein écran. Une variante paysage 16:9 (écran de verrouillage,
 * notification) pourra s'ajouter ici sur le même modèle.
 */
object ArtworkUrls {
    private const val MIN_SIZE_PX = 64
    private const val MAX_SIZE_PX = 1_200

    /** En dessous de cette taille demandée, la vignette d'origine suffit (pas de réécriture YouTube). */
    private const val YTIMG_MIN_SIZE_PX = 480

    private val googleHost = Regex("""^https?://([a-z0-9-]+\.)*(googleusercontent\.com|ggpht\.com)/""", RegexOption.IGNORE_CASE)
    private val ytimgVideo = Regex(
        """^(https?://(?:i|i9|img)\.ytimg\.com)/(vi|vi_webp)/([A-Za-z0-9_-]{11})/[^/?#]+$""",
        RegexOption.IGNORE_CASE,
    )
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
