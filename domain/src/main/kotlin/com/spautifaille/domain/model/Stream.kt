package com.spautifaille.domain.model

enum class AudioQuality { BEST, DATA_SAVER }

/**
 * Flux audio résolu. Durée de vie courte : NE JAMAIS PERSISTER.
 * [url] est soit une URL HTTP progressive, soit (si [dashManifest] non nul) ignorée au profit du manifeste.
 */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String?,
    val codec: String?,
    val bitrate: Int,
    val contentLength: Long?,
    /** En-têtes HTTP obligatoires pour lire l'URL (ex. User-Agent VisionOS). */
    val headers: Map<String, String>,
    /** Horodatage epoch ms après lequel l'URL est considérée expirée. */
    val expiresAtMs: Long,
    /** Manifeste DASH (XML) quand le flux n'est pas progressif. */
    val dashManifest: String? = null,
    /**
     * Niveau sonore du titre relatif à la cible de YouTube (-14 LUFS), en dB : positif = plus fort. `null` si
     * YouTube ne l'annonce pas. Stable par vidéo (contrairement à l'URL), donc mémorisable : voir `LoudnessStore`.
     */
    val loudnessDb: Float? = null,
)
