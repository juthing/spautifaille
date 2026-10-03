package com.spautifaille.data.newpipe

import com.spautifaille.domain.repository.LoudnessStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extrait le niveau sonore d'une réponse JSON du `player` InnerTube (client VisionOS, le seul utilisé par
 * l'extracteur). NewPipeExtractor ne l'expose pas (`playerResponse` est privé) : on lit donc la réponse au passage,
 * dans le `Downloader` (voir [PlayerLoudnessRecorder]).
 *
 * Champs lus (vérifiés sur de vraies réponses VisionOS, voir `PlayerResponseLoudnessTest`) :
 * - `playerConfig.audioConfig.loudnessDb` : ancien champ, déjà relatif à la cible (absent des réponses récentes) ;
 * - `playerConfig.audioConfig.trackAbsoluteLoudnessLkfs` (ou `perceptualLoudnessDb`, même valeur) : niveau absolu
 *   du titre en LUFS, à comparer à `loudnessTargetLkfs` (-14 par défaut) ;
 * - `streamingData.adaptiveFormats[].loudnessDb` : niveau relatif, par format audio (repli).
 *
 * Résultat : `loudnessDb` relatif à la cible, positif = plus fort. Toute absence ou anomalie donne `null`.
 */
internal object PlayerResponseLoudness {

    /** Cible de référence de YouTube quand la réponse ne la donne pas (`loudnessTargetLkfs`). */
    const val DEFAULT_TARGET_LKFS = -14.0

    data class Result(val videoId: String, val loudnessDb: Float)

    private val json = Json { isLenient = true }

    /** `null` si [body] n'est pas une réponse de lecture exploitable (jamais d'exception). */
    fun parse(body: String): Result? {
        // Évite de parser les réponses qui n'ont rien à voir (métadonnées Web, erreurs…).
        if (!body.contains("oudness")) return null
        return try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            val videoId = (root["videoDetails"] as? JsonObject)?.string("videoId")?.takeIf { it.isNotBlank() }
                ?: return null
            val db = loudnessDb(root) ?: return null
            Result(videoId, db)
        } catch (e: Exception) {
            null
        }
    }

    private fun loudnessDb(root: JsonObject): Float? {
        val audioConfig = (root["playerConfig"] as? JsonObject)?.get("audioConfig") as? JsonObject
        val fromConfig = audioConfig?.let { config ->
            config.number("loudnessDb") ?: run {
                val absolute = config.number("trackAbsoluteLoudnessLkfs") ?: config.number("perceptualLoudnessDb")
                val target = config.number("loudnessTargetLkfs") ?: DEFAULT_TARGET_LKFS
                absolute?.let { it - target }
            }
        }
        val value = fromConfig ?: firstAudioFormatLoudness(root)
        return value?.toFloat()?.takeIf { it.isFinite() }
    }

    private fun firstAudioFormatLoudness(root: JsonObject): Double? {
        val formats = (root["streamingData"] as? JsonObject)?.get("adaptiveFormats") as? JsonArray ?: return null
        return formats.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("mimeType")?.startsWith("audio/") == true }
            .mapNotNull { it.number("loudnessDb") }
            .firstOrNull()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
}

/**
 * Observe les réponses HTTP du [OkHttpDownloader] : sur celles du `player` InnerTube, extrait le niveau sonore et le
 * mémorise dans le [LoudnessStore]. Ne lève jamais et ne modifie pas la réponse.
 */
@Singleton
class PlayerLoudnessRecorder @Inject constructor(private val store: LoudnessStore) {

    fun onResponse(url: String, body: String) {
        if (!url.contains(PLAYER_ENDPOINT)) return
        try {
            PlayerResponseLoudness.parse(body)?.let { store.put(it.videoId, it.loudnessDb) }
        } catch (e: Exception) {
            // Une mémorisation ratée ne doit jamais faire échouer une requête NewPipe.
        }
    }

    private companion object {
        const val PLAYER_ENDPOINT = "/youtubei/v1/player"
    }
}

/** Mémoire factice (aucun stockage) pour les `OkHttpDownloader` construits hors injection. */
internal object NoOpLoudnessStore : LoudnessStore {
    override fun peek(videoId: String): Float? = null
    override suspend fun get(videoId: String): Float? = null
    override fun put(videoId: String, loudnessDb: Float) = Unit
}
