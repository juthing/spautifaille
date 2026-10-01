package com.spautifaille.domain.model

data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val thumbnailUrl: String?,
    val isSystem: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        /** Playlist système « Titres likés », créée à l'initialisation de la base, non supprimable. */
        const val LIKED_ID: Long = 1L

        /**
         * Playlist virtuelle « Téléchargés » : regroupe les titres dont le téléchargement est terminé.
         * Absente de la base (aucun schéma Room), l'id négatif ne peut pas entrer en collision avec une
         * playlist stockée (ids positifs auto-générés). Les ViewModels la reconstruisent depuis `DownloadRepository`.
         */
        const val DOWNLOADED_ID: Long = -2L
    }
}

/** Entrée d'une playlist locale. [entryId] distingue deux occurrences du même titre. */
data class PlaylistEntry(
    val entryId: Long,
    val position: Int,
    val track: Track,
)

data class PlaylistWithTracks(
    val playlist: Playlist,
    val entries: List<PlaylistEntry>,
)

data class HistoryEntry(
    val id: Long,
    val track: Track,
    val playedAt: Long,
)

data class PlayCount(
    val track: Track,
    val count: Int,
    val lastPlayedAt: Long,
)
