package com.spautifaille.domain.recommendation

import com.spautifaille.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Source externe de similarité. On ne développe pas d'algorithme de recommandation propre.
 * Implémentée : `YouTubeRelatedSource` (`:data`).
 * Extension future prévue : `LastFmSource` (track.getSimilar / artist.getSimilar) avec une clé API
 * saisie par l'utilisateur dans les paramètres ([com.spautifaille.domain.model.AppSettings.lastFmApiKey]),
 * jamais embarquée dans l'APK.
 */
interface RecommendationSource {
    val id: String
    suspend fun similar(seed: Track, limit: Int = 25): List<Track>
}

data class Discovery(
    val tracks: List<Track>,
    val generatedAt: Long,
)

interface DiscoveryRepository {
    fun observe(): Flow<Discovery?>
    /** Recalcule (réseau). Appelé par le worker périodique ou à la demande. */
    suspend fun refresh()
    fun scheduleRefresh()
}
