package com.spautifaille.ui.playlist

import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.Track

/**
 * Logique pure de disponibilité des titres selon le réseau : hors ligne, seuls les titres lisibles sans réseau
 * (téléchargés ou présents dans le cache de streaming, voir `OfflineAvailability`) peuvent être lus.
 * Partagée par la liste des playlists, la page d'une playlist et l'historique.
 */

/** Un titre est lisible s'il est dans [playableOfflineIds] ou si l'appareil est en ligne. */
internal fun isTrackAvailable(trackId: String, playableOfflineIds: Set<String>, isOffline: Boolean): Boolean =
    !isOffline || trackId in playableOfflineIds

/** Entrées lisibles, ordre conservé. En ligne : toutes. */
internal fun List<PlaylistEntry>.availableEntries(playableOfflineIds: Set<String>, isOffline: Boolean): List<PlaylistEntry> =
    if (!isOffline) this else filter { it.track.id in playableOfflineIds }

/** Titres lisibles, ordre conservé. En ligne : tous. */
internal fun List<Track>.availableTracks(playableOfflineIds: Set<String>, isOffline: Boolean): List<Track> =
    if (!isOffline) this else filter { it.id in playableOfflineIds }

/** Téléchargements terminés, du plus récent au plus ancien (ordre de la playlist « Téléchargés »). */
internal fun List<Download>.completedDownloads(): List<Download> =
    filter { it.state == DownloadState.COMPLETED }.sortedByDescending { it.createdAt }

/**
 * Représente les téléchargements terminés comme les entrées d'une playlist. L'`entryId` est dérivé de la
 * position : la liste n'est ni réordonnable ni éditable, la clé n'a besoin d'être unique que dans la liste.
 */
internal fun List<Download>.toPlaylistEntries(): List<PlaylistEntry> =
    completedDownloads().mapIndexed { index, download ->
        PlaylistEntry(entryId = index + 1L, position = index, track = download.track)
    }

/** Playlist virtuelle « Téléchargés » ([Playlist.DOWNLOADED_ID]). Le nom affiché est résolu par l'UI. */
internal fun downloadedPlaylist(trackCount: Int): Playlist = Playlist(
    id = Playlist.DOWNLOADED_ID,
    name = DOWNLOADED_PLAYLIST_NAME,
    trackCount = trackCount,
    thumbnailUrl = null,
    isSystem = true,
    createdAt = 0L,
    updatedAt = 0L,
)

private const val DOWNLOADED_PLAYLIST_NAME = "Téléchargés"
