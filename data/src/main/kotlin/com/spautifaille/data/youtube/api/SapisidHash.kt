package com.spautifaille.data.youtube.api

import java.security.MessageDigest

/**
 * En-tête `Authorization: SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + origin)>` des requêtes authentifiées par
 * cookies (même algorithme que `get_authorization` de ytmusicapi, `helpers.py`).
 */
internal object SapisidHash {

    /** Valeur de l'en-tête `Authorization`. [epochSeconds] : horodatage Unix en secondes. */
    fun authorization(sapisid: String, origin: String, epochSeconds: Long): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$epochSeconds $sapisid $origin".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${epochSeconds}_$hex"
    }

    /**
     * Valeur du cookie SAPISID dans l'en-tête `Cookie` : `SAPISID`, à défaut `__Secure-3PAPISID` (utilisé par
     * ytmusicapi) ou `__Secure-1PAPISID` (même valeur). `null` si aucun n'est présent.
     */
    fun sapisidFromCookie(cookieHeader: String): String? {
        val cookies = parseCookieHeader(cookieHeader)
        return cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["__Secure-1PAPISID"]
    }

    /** `a=1; b=2` → map. Les guillemets autour des valeurs sont retirés. */
    fun parseCookieHeader(header: String): Map<String, String> =
        header.split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val name = part.substring(0, index).trim()
                if (name.isEmpty()) return@mapNotNull null
                name to part.substring(index + 1).trim().removeSurrounding("\"")
            }
            .toMap()
}
