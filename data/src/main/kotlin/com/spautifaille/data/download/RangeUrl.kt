package com.spautifaille.data.download

/**
 * Construction des URL de lecture par plages des flux YouTube (`googlevideo.com`).
 *
 * Les URL VisionOS ne se lisent pas avec un en-tête `Range` : la plage passe dans le paramètre de requête
 * `range=<début>-<fin>` (bornes incluses), accompagnée d'un compteur de requêtes `rn=<n>`, comme le fait
 * `YoutubeHttpDataSource` de NewPipe. Fonctions pures, testables sans réseau.
 */
internal object RangeUrl {

    private val CLEN_REGEX = Regex("""[?&;]clen=(\d+)""")

    /**
     * Renvoie [url] avec `range=[start]-[endInclusive]` et `rn=[requestNumber]` ; d'éventuels `range` / `rn`
     * déjà présents sont remplacés. Les autres paramètres (signature comprise) sont conservés tels quels,
     * sans ré-encodage.
     */
    fun build(url: String, start: Long, endInclusive: Long, requestNumber: Int): String {
        require(start >= 0 && endInclusive >= start) { "Plage invalide : $start-$endInclusive" }
        val questionMark = url.indexOf('?')
        val base = if (questionMark >= 0) url.substring(0, questionMark) else url
        val kept = if (questionMark >= 0) {
            url.substring(questionMark + 1)
                .split('&')
                .filter { it.isNotEmpty() && !it.startsWith("range=") && !it.startsWith("rn=") }
        } else {
            emptyList()
        }
        return (kept + "range=$start-$endInclusive" + "rn=$requestNumber")
            .joinToString(separator = "&", prefix = "$base?")
    }

    /** Taille totale annoncée par le paramètre `clen` des URL googlevideo, ou `null`. */
    fun contentLengthOf(url: String): Long? =
        CLEN_REGEX.find(url)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
}
