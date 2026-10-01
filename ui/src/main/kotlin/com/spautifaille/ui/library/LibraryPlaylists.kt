package com.spautifaille.ui.library

import com.spautifaille.domain.model.Playlist
import com.spautifaille.ui.playlist.downloadedPlaylist

/**
 * Liste affichée dans la Bibliothèque : « Titres likés » et « Téléchargés » épinglés en tête, puis les
 * playlists de l'utilisateur dans l'ordre du repository. Hors ligne, « Téléchargés » passe devant.
 *
 * @param stored playlists du repository (la playlist système likés comprise).
 */
internal fun orderLibraryPlaylists(
    stored: List<Playlist>,
    downloadedCount: Int,
    isOffline: Boolean,
): List<Playlist> {
    val liked = stored.firstOrNull { it.id == Playlist.LIKED_ID }
    val user = stored.filterNot { it.isSystem || it.id == Playlist.LIKED_ID }
    val downloaded = downloadedPlaylist(downloadedCount)
    val pinned = if (isOffline) listOfNotNull(downloaded, liked) else listOfNotNull(liked, downloaded)
    return pinned + user
}

/** Playlist épinglée (non modifiable ni supprimable). */
internal val Playlist.isPinned: Boolean
    get() = isSystem || id == Playlist.LIKED_ID || id == Playlist.DOWNLOADED_ID
