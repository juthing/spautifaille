package com.spautifaille.domain.repository

/**
 * Mémoire des niveaux sonores par titre (`loudnessDb`, voir `LoudnessNormalizer`). Contrairement à l'URL d'un
 * flux, le niveau sonore d'une vidéo est stable : on peut le conserver, notamment pour les titres téléchargés
 * ou lus hors ligne, qui ne passent plus par la résolution réseau.
 *
 * Aucune méthode ne lève : une valeur introuvable ou une écriture impossible est simplement ignorée.
 */
interface LoudnessStore {
    /** Valeur déjà en mémoire, sans accès disque ; `null` si inconnue ou pas encore chargée. */
    fun peek(videoId: String): Float?

    /** Valeur connue (charge le fichier au besoin) ; `null` si inconnue. */
    suspend fun get(videoId: String): Float?

    /** Mémorise [loudnessDb] (ignoré s'il n'est pas fini). Écriture disque différée. */
    fun put(videoId: String, loudnessDb: Float)
}
