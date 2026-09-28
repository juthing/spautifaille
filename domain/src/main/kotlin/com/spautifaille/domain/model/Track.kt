package com.spautifaille.domain.model

/**
 * Un titre jouable. [id] est l'identifiant vidéo YouTube (11 caractères), clé stable dans toute l'app.
 * Aucune URL de flux ici : elles expirent et sont résolues juste avant la lecture.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    /** URL de la chaîne YouTube de l'artiste (clé des abonnements), si connue. */
    val artistUrl: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

/** Chaîne YouTube / artiste. [url] sert d'identifiant. */
data class Artist(
    val url: String,
    val name: String,
    val avatarUrl: String? = null,
    val subscriberCount: Long? = null,
)

/** Playlist distante (YouTube / YouTube Music), non stockée localement. */
data class RemotePlaylist(
    val url: String,
    val name: String,
    val uploader: String? = null,
    val thumbnailUrl: String? = null,
    val trackCount: Long? = null,
)
