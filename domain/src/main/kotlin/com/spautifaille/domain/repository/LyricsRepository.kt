package com.spautifaille.domain.repository

import com.spautifaille.domain.lyrics.Lyrics
import com.spautifaille.domain.model.Track

interface LyricsRepository {
    /**
     * Paroles de [track], ou `null` si le fournisseur n'en a pas (résultat lui-même mis en cache).
     * Lève `AppException(AppError.Network)` si le réseau est indisponible et que rien n'est en cache.
     */
    suspend fun lyrics(track: Track): Lyrics?
}
