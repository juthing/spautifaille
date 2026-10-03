package com.spautifaille.ui.youtube

import com.spautifaille.domain.youtube.YouTubeCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Logique pure de la connexion par WebView (testable sans Android) : URL, détection de la session, analyse de la
 * configuration de la page, User-Agent. La capture elle-même (CookieManager, `evaluateJavascript`) est dans
 * `YouTubeLoginWebView`.
 *
 * Procédure reprise de Metrolist (`LoginScreen.kt`) : connexion Google avec retour sur `music.youtube.com`, cookie
 * `SAPISID` présent = connecté, `VISITOR_DATA` / `DATASYNC_ID` lus dans `window.yt.config_`.
 */
object LoginSession {
    const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    const val COOKIE_URL = "https://music.youtube.com"
    private const val MUSIC_HOST = "music.youtube.com"

    /** Script évalué dans la page : renvoie une chaîne JSON `{"v": VISITOR_DATA, "d": DATASYNC_ID, "i": SESSION_INDEX}`. */
    const val CONFIG_SCRIPT =
        "(function(){try{var c=window.yt&&window.yt.config_;return JSON.stringify({" +
            "v:(c&&c.VISITOR_DATA)||null,d:(c&&c.DATASYNC_ID)||null," +
            "i:(c&&c.SESSION_INDEX!=null)?String(c.SESSION_INDEX):'0'});}catch(e){return null;}})()"

    /** Une URL `https://music.youtube.com/...` : page où la configuration YouTube Music est disponible. */
    fun isMusicPage(url: String?): Boolean {
        if (url == null) return false
        val withoutScheme = url.removePrefix("https://")
        if (withoutScheme == url) return false
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return host == MUSIC_HOST
    }

    /** Le cookie de session Google est présent : l'utilisateur est connecté. */
    fun hasSessionCookie(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val names = cookieHeader.split(';').mapNotNull { it.substringBefore('=', "").trim().ifEmpty { null } }
        return "SAPISID" in names || "__Secure-3PAPISID" in names
    }

    /** Résultat de [CONFIG_SCRIPT] : données de la page nécessaires à InnerTube. */
    data class PageConfig(val visitorData: String?, val dataSyncId: String?, val sessionIndex: String)

    /**
     * Analyse la valeur renvoyée par `evaluateJavascript` : un littéral JSON de chaîne (donc entre guillemets,
     * échappé) contenant lui-même l'objet JSON ; `null` / `"null"` / vide / illisible donnent `null`.
     */
    fun parseConfig(raw: String?): PageConfig? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val inner = Json.parseToJsonElement(raw).jsonPrimitive.takeIf { it.isString }?.content ?: return null
            val obj: JsonObject = Json.parseToJsonElement(inner).jsonObject
            PageConfig(
                visitorData = obj.string("v"),
                dataSyncId = obj.string("d"),
                sessionIndex = obj.string("i").orEmpty().filter(Char::isDigit).ifEmpty { "0" },
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Identifiants prêts à être validés. `DATASYNC_ID` de la forme `<id>||` ou `<id>||<autre>` : seule la partie
     * avant `||` est utilisée (comme Metrolist). Sans `VISITOR_DATA`, la page n'est pas assez chargée : `null`.
     */
    fun credentials(cookie: String, config: PageConfig?): YouTubeCredentials? {
        val visitorData = config?.visitorData?.takeIf { it.isNotBlank() } ?: return null
        return YouTubeCredentials(
            cookie = cookie,
            visitorData = visitorData,
            dataSyncId = config.dataSyncId?.substringBefore("||")?.takeIf { it.isNotBlank() },
            authUser = config.sessionIndex,
        )
    }

    /**
     * Google refuse la connexion dans une WebView reconnaissable (« Ce navigateur n'est peut-être pas sécurisé »).
     * Contournement usuel : retirer du User-Agent le marqueur `; wv` et le jeton `Version/x.y` propres aux WebView
     * pour ressembler à Chrome mobile. Non vérifié sans appareil (voir CLAUDE.md).
     */
    fun browserUserAgent(webViewUserAgent: String): String =
        webViewUserAgent
            .replace("; wv", "")
            .replace(Regex("""\sVersion/\d+(\.\d+)*"""), "")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
