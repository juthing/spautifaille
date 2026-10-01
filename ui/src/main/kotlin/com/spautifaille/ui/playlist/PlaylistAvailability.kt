package com.spautifaille.ui.playlist

import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.Track

/**
 * Logique pure de disponibilité des titres selon le réseau : hors ligne, seuls les titres téléchargés
 * peuvent être lus. Partagée par la liste des playlists et la page d'une playlist.
 */

/** Un titre est lisible s'il est téléchargé ou si l'appareil est en ligne. */
internal fun isTrackAvailable(trackId: String, downloadedIds: Set<String>, isOffline: Boolean): Boolean =
    !isOffline || trackId in downloadedIds

/** Entrées lisibles, ordre conservé. En ligne : toutes. */
internal fun List<PlaylistEntry>.availableEntries(downloadedIds: Set<String>, isOffline: Boolean): List<PlaylistEntry> =
    if (!isOffline) this else filter { it.track.id in downloadedIds }

/** Titres lisibles, ordre conservé. En ligne : tous. */
internal fun List<Track>.availableTracks(downloadedIds: Set<String>, isOffline: Boolean): List<Track> =
    if (!isOffline) this else filter { it.id in downloadedIds }

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
