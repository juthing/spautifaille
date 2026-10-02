package com.spautifaille.data.youtube.api

/** Titre d'une playlist / des likes côté YouTube. [setVideoId] identifie l'entrée dans une playlist (suppression, déplacement). */
internal data class RemoteTrack(
    val videoId: String,
    val setVideoId: String?,
    val title: String,
    val artist: String?,
    val artistChannelId: String?,
    val album: String?,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    val isAvailable: Boolean = true,
)

/** Playlist YouTube complète (toutes les pages), dans l'ordre. */
internal data class RemotePlaylist(
    val id: String,
    val title: String?,
    /** Modifiable par le compte (propriétaire). */
    val isOwned: Boolean,
    val trackCount: Int?,
    val items: List<RemoteTrack>,
)

/** Playlist de la bibliothèque du compte (liste de la page « Bibliothèque > Playlists »). */
internal data class RemoteLibraryEntry(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val trackCount: Int?,
    val isOwned: Boolean,
)

internal data class RemoteChannel(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
)

/** Une page de résultats paginés. [skipped] = éléments ignorés faute de champ indispensable (diagnostic). */
internal data class Page<T>(
    val items: List<T>,
    val continuation: String?,
    val skipped: Int = 0,
)

/** Première page d'une playlist : en-tête + éléments. */
internal data class PlaylistPage(
    val playlistId: String?,
    val title: String?,
    val isOwned: Boolean,
    val trackCount: Int?,
    val page: Page<RemoteTrack>,
)

/** Résultat d'un ajout dans une playlist : l'entrée créée porte un nouveau `setVideoId`. */
internal data class AddedEntry(val videoId: String, val setVideoId: String?)

/**
 * Réponse InnerTube de forme inattendue (champ manquant, API modifiée). Convertie en
 * `AppException(AppError.YouTubeSyncFailed(endpoint, detail))`, visible dans le mode diagnostic.
 */
internal class YouTubeParseException(val endpoint: String, val detail: String) :
    Exception("$endpoint : $detail")
