package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.AudioQuality

/** Conteneur / codec audio, réduit à ce qui compte pour le choix. */
internal enum class AudioContainer(val rank: Int) {
    /** WEBMA_OPUS (itag 251...) : meilleur rapport qualité/débit. */
    OPUS(2),
    M4A(1),
    OTHER(0),
}

internal enum class AudioDelivery { PROGRESSIVE_HTTP, DASH, OTHER }

internal enum class AudioTrackKind { ORIGINAL, OTHER }

/**
 * Modèle minimal d'un `AudioStream` NewPipe, sans dépendance à NewPipe (testable).
 * [index] est la position dans la liste d'origine.
 */
internal data class AudioCandidate(
    val index: Int,
    /** Débit moyen en kbit/s, [UNKNOWN_BITRATE] si inconnu. */
    val bitrateKbps: Int,
    val container: AudioContainer,
    val isUrl: Boolean,
    val delivery: AudioDelivery,
    /** `null` = piste inconnue (vidéo mono-piste), traitée comme originale. */
    val trackKind: AudioTrackKind? = null,
    val itag: Int = -1,
) {
    companion object {
        const val UNKNOWN_BITRATE = -1
    }
}

internal object AudioStreamSelector {
    /** Seuil sous lequel un flux est jugé trop dégradé pour le mode économie de données. */
    const val DATA_SAVER_MIN_KBPS = 48

    private const val ITAG_OPUS_160 = 251
    private const val ITAG_AAC_128 = 140

    /**
     * Choisit un flux :
     * 1. écarte les pistes doublées / descriptives s'il existe une piste originale ;
     * 2. préfère un flux progressif HTTP (`isUrl`), sinon un flux DASH dont le contenu est un manifeste ;
     * 3. BEST = débit maximal (égalité : Opus/itag 251, puis M4A/itag 140) ;
     *    DATA_SAVER = plus petit débit >= 48 kbit/s (à défaut, le plus élevé disponible).
     */
    fun select(candidates: List<AudioCandidate>, quality: AudioQuality): AudioCandidate? {
        if (candidates.isEmpty()) return null

        val originals = candidates.filter { it.trackKind == null || it.trackKind == AudioTrackKind.ORIGINAL }
        val pool = originals.ifEmpty { candidates }

        val progressive = pool.filter { it.isUrl && it.delivery == AudioDelivery.PROGRESSIVE_HTTP }
        val ranked = progressive.ifEmpty { pool.filter { !it.isUrl && it.delivery == AudioDelivery.DASH } }
        if (ranked.isEmpty()) return null

        return when (quality) {
            AudioQuality.BEST -> ranked.maxWithOrNull(bestComparator)
            AudioQuality.DATA_SAVER -> {
                val acceptable = ranked.filter { it.bitrateKbps >= DATA_SAVER_MIN_KBPS }
                if (acceptable.isEmpty()) ranked.maxWithOrNull(bestComparator)
                else acceptable.minWithOrNull(dataSaverComparator)
            }
        }
    }

    private val preferenceComparator: Comparator<AudioCandidate> =
        compareBy<AudioCandidate> { it.container.rank }
            .thenBy { it.itag == ITAG_OPUS_160 }
            .thenBy { it.itag == ITAG_AAC_128 }

    /** Le "plus grand" est le meilleur. */
    private val bestComparator: Comparator<AudioCandidate> =
        compareBy<AudioCandidate> { it.bitrateKbps }.then(preferenceComparator)

    /** Le "plus petit" est le meilleur : débit faible, puis meilleur codec à débit égal. */
    private val dataSaverComparator: Comparator<AudioCandidate> =
        compareBy<AudioCandidate> { it.bitrateKbps }.then(preferenceComparator.reversed())
}
