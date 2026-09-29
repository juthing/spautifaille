package com.spautifaille.domain.recommendation

import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.first
import kotlin.random.Random

data class SeedConfig(
    /** Derniers titres likés. */
    val likedCount: Int = 5,
    /** Titres les plus écoutés sur [topPlayedWindowDays] jours. */
    val topPlayedCount: Int = 5,
    val topPlayedWindowDays: Int = 30,
    /** Titres tirés au hasard dans les playlists locales. */
    val playlistCount: Int = 3,
    /** Nombre maximal de playlists parcourues pour le tirage aléatoire. */
    val maxPlaylistsScanned: Int = 5,
)

/**
 * Choisit les titres de départ de la découverte : derniers likés, plus écoutés du mois, tirage aléatoire dans
 * les playlists. Dédoublonné par id, dans cet ordre de priorité. Liste vide pour un nouvel utilisateur.
 */
class SeedSelector(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val config: SeedConfig = SeedConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun select(random: Random = Random.Default): List<Track> {
        val chosen = LinkedHashMap<String, Track>()

        if (config.likedCount > 0) {
            library.recentlyLiked(config.likedCount).forEach { chosen.putIfAbsent(it.id, it) }
        }
        if (config.topPlayedCount > 0) {
            val since = clock() - config.topPlayedWindowDays * DAY_MS
            // Marge pour compenser les doublons avec les likés.
            library.mostPlayed(config.topPlayedCount * 2, since)
                .map { it.track }
                .filter { it.id !in chosen }
                .distinctBy { it.id }
                .take(config.topPlayedCount)
                .forEach { chosen[it.id] = it }
        }
        if (config.playlistCount > 0) {
            val pool = LinkedHashMap<String, Track>()
            playlists.observePlaylists().first()
                .filter { it.trackCount > 0 }
                .shuffled(random)
                .take(config.maxPlaylistsScanned)
                .forEach { playlist ->
                    playlists.observePlaylist(playlist.id).first()?.entries?.forEach { e ->
                        if (e.track.id !in chosen) pool.putIfAbsent(e.track.id, e.track)
                    }
                }
            pool.values.toList().shuffled(random).take(config.playlistCount).forEach { chosen[it.id] = it }
        }
        return chosen.values.toList()
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
