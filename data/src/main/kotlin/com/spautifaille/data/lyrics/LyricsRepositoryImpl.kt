package com.spautifaille.data.lyrics

import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.lyrics.Lyrics
import com.spautifaille.domain.lyrics.LyricsCandidate
import com.spautifaille.domain.lyrics.LyricsMatcher
import com.spautifaille.domain.lyrics.LyricsQuery
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.LyricsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Paroles via LRCLIB avec cache disque. Les erreurs (réseau...) ne sont jamais mises en cache ; « pas de
 * paroles » l'est (voir [FileLyricsCache]).
 *
 * Stratégie : correspondance exacte `/api/get` (artiste complet, puis artiste principal), puis recherche
 * `track_name` + `artist_name`, puis recherche libre `q`, avec choix du meilleur candidat ([LyricsMatcher]).
 */
class LyricsRepositoryImpl @Inject constructor(
    private val client: LrclibClient,
    private val cache: LyricsCache,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LyricsRepository {

    override suspend fun lyrics(track: Track): Lyrics? {
        withContext(ioDispatcher) { cache.read(track.id) }?.let { return it.lyrics }
        val result = fetch(LyricsQuery.from(track))
        withContext(ioDispatcher) { cache.write(track.id, result) }
        return result
    }

    private suspend fun fetch(query: LyricsQuery): Lyrics? {
        for (artist in listOf(query.artistName, query.primaryArtist).distinct()) {
            client.get(query.trackName, artist, query.albumName, query.durationSec)?.toLyrics()?.let { return it }
        }
        val searches = buildList<suspend () -> List<LyricsCandidate>> {
            add { client.search(trackName = query.trackName, artistName = query.artistName) }
            if (query.primaryArtist != query.artistName) {
                add { client.search(trackName = query.trackName, artistName = query.primaryArtist) }
            }
            add { client.search(q = query.searchText) }
        }
        for (search in searches) {
            LyricsMatcher.select(query, search())?.let { return it }
        }
        return null
    }
}
