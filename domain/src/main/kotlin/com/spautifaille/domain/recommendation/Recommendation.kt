package com.spautifaille.domain.recommendation

import com.spautifaille.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Source externe de similarité. On ne développe pas d'algorithme de recommandation propre : la similarité
 * est déléguée à des services tiers, le reste (mélange, dédoublonnage, exclusions, diversité) est calculé
 * localement par [DiscoveryEngine].
 *
 * Implémentée : `YouTubeRelatedSource` (`:data`, id `youtube_related`).
 *
 * ### Extension future : `LastFmSource`
 * Une implémentation Last.fm s'ajoutera sans toucher au reste : `track.getSimilar` (à partir de
 * `seed.artist` + `seed.title`) et, en repli, `artist.getSimilar` puis `artist.getTopTracks`. Les titres
 * renvoyés n'ont pas d'id YouTube : ils devront être résolus par recherche (`StreamRepository.search` puis
 * `TrackMatcher`) avant d'être renvoyés par [similar]. La clé API est saisie par l'utilisateur dans les
 * paramètres ([com.spautifaille.domain.model.AppSettings.lastFmApiKey]) et lue à chaque appel ; elle n'est
 * jamais embarquée dans l'APK. Sans clé, la source renvoie simplement une liste vide. Il suffira de la
 * déclarer dans le multibinding Hilt `Set<RecommendationSource>` (`@IntoSet`).
 *
 * Contrat : [similar] lève `AppException` en cas d'échec réseau ; l'appelant tolère l'échec d'une source
 * ou d'un titre de départ. Les titres renvoyés sont triés du plus au moins pertinent.
 */
interface RecommendationSource {
    val id: String
    suspend fun similar(seed: Track, limit: Int = 25): List<Track>
}

data class Discovery(
    val tracks: List<Track>,
    val generatedAt: Long,
) {
    /** Vrai si la découverte a plus de [maxAgeMs] à l'instant [now]. */
    fun isStale(now: Long, maxAgeMs: Long = DISCOVERY_REFRESH_INTERVAL_MS): Boolean = now - generatedAt > maxAgeMs
}

/** Durée de validité d'une découverte calculée (12 h) : période du rafraîchissement en arrière-plan. */
const val DISCOVERY_REFRESH_INTERVAL_MS: Long = 12L * 60 * 60 * 1000

interface DiscoveryRepository {
    fun observe(): Flow<Discovery?>

    /**
     * Recalcule (réseau). Appelé par le worker périodique ou à la demande. Si un calcul est déjà en cours,
     * attend sa fin au lieu d'en lancer un second. Lève `AppException` si tous les titres de départ ont échoué.
     */
    suspend fun refresh()

    /** Programme le rafraîchissement périodique (12 h). Idempotent. */
    fun scheduleRefresh()
}
