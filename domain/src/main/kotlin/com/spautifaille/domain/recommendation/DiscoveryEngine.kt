package com.spautifaille.domain.recommendation

import com.spautifaille.domain.matching.TextNormalizer
import com.spautifaille.domain.model.Track
import kotlin.random.Random

/** Un titre proposé par la source [sourceId]. */
data class Candidate(val track: Track, val sourceId: String)

/** Un titre retenu, avec le titre de départ [seedId] dont il est issu. */
data class DiscoveryItem(val track: Track, val seedId: String, val sourceId: String)

data class DiscoveryConfig(
    /** Nombre maximal de titres renvoyés. */
    val limit: Int = 50,
    /** Nombre maximal de titres d'un même artiste dans le résultat. */
    val maxPerArtist: Int = 3,
    /** Écarte les titres plus courts (jingles, extraits) ; les durées inconnues sont conservées. */
    val minDurationMs: Long = 60_000L,
    /** Écarte les titres plus longs (mix de 1 h, albums complets, podcasts). */
    val maxDurationMs: Long = 15 * 60_000L,
    val filterByDuration: Boolean = true,
)

/**
 * Entrées du moteur.
 * @param seeds titres de départ, dans l'ordre de priorité.
 * @param similarBySeed titres similaires par id de titre de départ (ordre de pertinence conservé).
 * @param recentlyPlayedIds écoutés récemment (sur N jours) : exclus.
 * @param libraryIds tous les titres des playlists locales : exclus.
 * @param likedIds titres likés : exclus.
 */
data class DiscoveryInput(
    val seeds: List<Track>,
    val similarBySeed: Map<String, List<Candidate>>,
    val recentlyPlayedIds: Set<String> = emptySet(),
    val libraryIds: Set<String> = emptySet(),
    val likedIds: Set<String> = emptySet(),
)

/**
 * Assemble la liste « Découverte » à partir des résultats des sources de similarité. Pur et déterministe pour
 * un [Random] donné.
 *
 * 1. Fusion round-robin entre les titres de départ (aucun ne domine), dédoublonnage par id **et** par
 *    « artiste + titre nettoyé » (`Song (Official Video)` = `Song (Lyrics)`), exclusion des titres de départ
 *    (et de leurs doublons), des écoutes récentes, de la bibliothèque et des likés, filtre de durée.
 * 2. Limite par artiste ([DiscoveryConfig.maxPerArtist]) puis [DiscoveryConfig.limit], en suivant l'ordre du
 *    round-robin (les premiers de chaque seed sont prioritaires).
 * 3. Mélange, puis réordonnancement sans deux titres consécutifs du même artiste quand c'est possible.
 */
class DiscoveryEngine(private val config: DiscoveryConfig = DiscoveryConfig()) {

    fun build(input: DiscoveryInput, random: Random = Random.Default): List<DiscoveryItem> {
        if (config.limit <= 0 || input.seeds.isEmpty()) return emptyList()
        val selected = selectCandidates(input)
        if (selected.size < 2) return selected
        return orderWithoutRepeats(selected.shuffled(random))
    }

    private fun selectCandidates(input: DiscoveryInput): List<DiscoveryItem> {
        val excludedIds = HashSet<String>().apply {
            addAll(input.recentlyPlayedIds)
            addAll(input.libraryIds)
            addAll(input.likedIds)
            input.seeds.forEach { add(it.id) }
        }
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        input.seeds.forEach { seenKeys += dedupKey(it) }

        val queues = input.seeds.distinctBy { it.id }.map { seed ->
            seed.id to input.similarBySeed[seed.id].orEmpty().iterator()
        }
        val perArtist = HashMap<String, Int>()
        val result = ArrayList<DiscoveryItem>()
        var progress = true
        while (progress && result.size < config.limit) {
            progress = false
            for ((seedId, iterator) in queues) {
                // Avance jusqu'au premier candidat acceptable de ce titre de départ pour ce tour.
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    progress = true
                    val track = candidate.track
                    if (track.id in excludedIds || track.id in seenIds) continue
                    if (!durationOk(track)) continue
                    val key = dedupKey(track)
                    if (key in seenKeys) continue
                    val artist = artistKey(track)
                    if ((perArtist[artist] ?: 0) >= config.maxPerArtist) continue
                    seenIds += track.id
                    seenKeys += key
                    perArtist[artist] = (perArtist[artist] ?: 0) + 1
                    result += DiscoveryItem(track, seedId, candidate.sourceId)
                    break
                }
                if (result.size >= config.limit) break
            }
        }
        return result
    }

    private fun durationOk(track: Track): Boolean {
        if (!config.filterByDuration) return true
        val d = track.durationMs ?: return true
        return d in config.minDurationMs..config.maxDurationMs
    }

    /**
     * Réordonne [shuffled] (ordre aléatoire) pour éviter deux artistes identiques consécutifs. À chaque pas :
     * si un artiste (différent du dernier) n'a plus assez de « séparateurs » pour être placé plus tard, il est
     * forcé ; sinon on prend le premier titre de l'ordre aléatoire dont l'artiste diffère du précédent.
     * Aucune répétition n'est produite quand la répartition le permet.
     */
    private fun orderWithoutRepeats(shuffled: List<DiscoveryItem>): List<DiscoveryItem> {
        val remaining = shuffled.toMutableList()
        val counts = HashMap<String, Int>()
        remaining.forEach { counts.merge(artistKey(it.track), 1, Int::plus) }
        val out = ArrayList<DiscoveryItem>(shuffled.size)
        var last: String? = null
        while (remaining.isNotEmpty()) {
            val n = remaining.size
            val forced = counts.entries
                .filter { it.key != last && it.value > 0 && 2 * it.value - 1 >= n }
                .maxByOrNull { it.value }?.key
            val index = if (forced != null) {
                remaining.indexOfFirst { artistKey(it.track) == forced }
            } else {
                remaining.indexOfFirst { artistKey(it.track) != last }.takeIf { it >= 0 } ?: 0
            }
            val item = remaining.removeAt(index)
            val artist = artistKey(item.track)
            counts.merge(artist, -1, Int::plus)
            out += item
            last = artist
        }
        return out
    }

    companion object {
        /** Clé de comparaison d'artiste : premier artiste crédité, sans suffixes de chaîne (VEVO, Topic...). */
        fun artistKey(track: Track): String {
            val first = TextNormalizer.splitArtists(track.artist).firstOrNull() ?: track.artist
            // Forme compacte : `The Weeknd` = `TheWeekndVEVO`, `AC/DC` = `ACDC`.
            return TextNormalizer.normalizeArtist(first).replace(" ", "").ifEmpty { "\u0000${track.id}" }
        }

        /** Clé de dédoublonnage « artiste + titre nettoyé » (décorations, feat. et préfixe « Artiste - » retirés). */
        fun dedupKey(track: Track): String {
            val artist = artistKey(track)
            val cleaned = TextNormalizer.extractFeaturing(TextNormalizer.stripDecorations(track.title)).title
            var title = TextNormalizer.normalize(cleaned)
            for (prefix in listOf(TextNormalizer.normalize(track.artist), artist)) {
                if (prefix.isNotEmpty() && title.startsWith("$prefix ") && title.length > prefix.length + 1) {
                    title = title.removePrefix("$prefix ")
                    break
                }
            }
            if (title.isEmpty()) return "id:${track.id}"
            return "$artist|$title"
        }
    }
}
