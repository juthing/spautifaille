package com.spautifaille.data.importer

import android.content.Context
import com.spautifaille.data.R
import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.PlaylistImporter
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.repository.StreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Importe une playlist YouTube / YouTube Music publique : lit toutes les pages, les ids YouTube sont connus (pas de matching). */
class YouTubePlaylistImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
) : PlaylistImporter {

    override fun canHandle(source: ImportSource): Boolean =
        source is ImportSource.Url && streamRepository.isPlaylistUrl(source.url.trim())

    override suspend fun read(source: ImportSource): List<ImportedPlaylist> {
        val url = (source as? ImportSource.Url)?.url?.trim()
            ?: throw ImportException(context.getString(R.string.data_import_error_unsupported_source))
        var name = ""
        val tracks = ArrayList<ImportedTrack>()
        var page: PageToken? = null
        var pages = 0
        do {
            val result = streamRepository.remotePlaylist(url, page)
            if (pages == 0) name = result.playlist.name
            pages++
            result.tracks.items.mapTo(tracks) { t ->
                ImportedTrack(
                    title = t.title,
                    artists = listOf(t.artist).filter { it.isNotBlank() },
                    album = t.album,
                    durationMs = t.durationMs,
                    youtubeId = t.id,
                )
            }
            // Arrêt si une page est vide (jeton qui ne progresse plus) ou si un plafond est atteint.
            page = if (result.tracks.items.isEmpty() || tracks.size >= MAX_TRACKS || pages >= MAX_PAGES) {
                null
            } else {
                result.tracks.next
            }
        } while (page != null)

        if (tracks.isEmpty()) throw ImportException(context.getString(R.string.data_import_error_playlist_empty))
        return listOf(
            ImportedPlaylist(
                name = name.ifBlank { context.getString(R.string.data_import_default_playlist_youtube) },
                tracks = tracks.take(MAX_TRACKS),
                sourceFormat = ImportFormat.YOUTUBE_URL,
            ),
        )
    }

    companion object {
        const val MAX_TRACKS = 5000
        private const val MAX_PAGES = 200
    }
}
