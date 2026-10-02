package com.spautifaille.domain.repository

import com.spautifaille.domain.model.ArtistDetails
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.Paged
import com.spautifaille.domain.model.RemotePlaylistPage
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.model.TrackStats

/**
 * Point d'accès unique aux contenus distants. L'implémentation NewPipe vit dans `:data` (package `newpipe`)
 * et peut être remplacée sans toucher au reste de l'app.
 *
 * Toutes les fonctions sont `suspend`, s'exécutent hors du thread principal et lèvent
 * [com.spautifaille.domain.error.AppException] en cas d'échec.
 */
interface StreamRepository {
    suspend fun suggestions(query: String): List<String>

    suspend fun search(query: String, filter: SearchFilter, page: PageToken? = null): Paged<SearchResult>

    /** Métadonnées d'un titre (peut être servi depuis le cache). */
    suspend fun track(videoId: String): Track

    /** Résout l'URL du flux audio. Appelé juste avant la lecture ; ne jamais persister le résultat. */
    suspend fun resolveAudio(videoId: String, quality: AudioQuality): ResolvedStream

    /** Vues, J'aime et date de publication d'un titre (valeurs inconnues = null ; cache mémoire côté implémentation). */
    suspend fun trackStats(videoId: String): TrackStats

    /** Titres liés (« À suivre » YouTube). */
    suspend fun related(videoId: String): List<Track>

    /** Titres d'un Mix YouTube Music (`RDAMVM<videoId>`). Peut renvoyer une liste vide si non supporté. */
    suspend fun mix(videoId: String): List<Track>

    /** Playlist publique ou non répertoriée (youtube.com ou music.youtube.com). */
    suspend fun remotePlaylist(url: String, page: PageToken? = null): RemotePlaylistPage

    suspend fun artist(url: String): ArtistDetails

    /** Vérifie si une URL désigne une playlist YouTube reconnue. */
    fun isPlaylistUrl(url: String): Boolean
}
