package com.spautifaille.data.recommendation

import com.spautifaille.domain.model.Track
import com.spautifaille.domain.recommendation.RecommendationSource
import com.spautifaille.domain.repository.StreamRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Similarité déléguée à YouTube : les « titres liés » de la page de lecture (`StreamRepository.related`,
 * ~20 titres). Quand ils sont moins de [MIN_RELATED] (ou que la requête échoue), on complète avec le Mix
 * YouTube Music du titre (`RDAMVM<id>`, ~25 titres, `StreamRepository.mix`) ; si le Mix ne renvoie rien non
 * plus, l'erreur d'origine est relancée.
 */
@Singleton
class YouTubeRelatedSource @Inject constructor(
    private val streams: StreamRepository,
) : RecommendationSource {

    override val id: String = ID

    override suspend fun similar(seed: Track, limit: Int): List<Track> {
        val related = try {
            streams.related(seed.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val mix = streams.mix(seed.id)
            if (mix.isEmpty()) throw e
            return clean(seed, mix, limit)
        }
        val merged = if (related.size >= MIN_RELATED) related else related + streams.mix(seed.id)
        return clean(seed, merged, limit)
    }

    private fun clean(seed: Track, tracks: List<Track>, limit: Int): List<Track> =
        tracks.filter { it.id != seed.id }.distinctBy { it.id }.take(limit)

    companion object {
        const val ID = "youtube_related"

        /** En dessous, les titres liés sont complétés par le Mix. */
        const val MIN_RELATED = 10
    }
}
