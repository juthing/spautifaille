package com.spautifaille.domain.importer

import com.spautifaille.domain.model.Track
import kotlinx.coroutines.flow.Flow

/** Source d'import choisie par l'utilisateur. */
sealed interface ImportSource {
    data class Url(val url: String) : ImportSource
    /** Fichier choisi via le Storage Access Framework. [uri] = content:// URI sérialisée. */
    data class File(val uri: String, val displayName: String?, val mimeType: String?) : ImportSource
}

/** Titre tel que lu dans la source externe, avant matching. */
data class ImportedTrack(
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    /** Renseigné quand la source donne directement l'id YouTube (Takeout, playlist YouTube) : pas de matching. */
    val youtubeId: String? = null,
) {
    val primaryArtist: String? get() = artists.firstOrNull()
}

data class ImportedPlaylist(
    val name: String,
    val tracks: List<ImportedTrack>,
    val sourceFormat: ImportFormat,
)

enum class ImportFormat { YOUTUBE_URL, EXPORTIFY_CSV, SPOTIFY_JSON, TAKEOUT_CSV, GENERIC_CSV, GENERIC_JSON }

/** Lecteur d'une source externe. Une implémentation par source ; les parsers sont purs et testés. */
interface PlaylistImporter {
    fun canHandle(source: ImportSource): Boolean
    /** Peut renvoyer plusieurs playlists (ex. export de données Spotify). */
    suspend fun read(source: ImportSource): List<ImportedPlaylist>
}

enum class MatchStatus { MATCHED, NEEDS_REVIEW, NOT_FOUND, PENDING }

data class MatchCandidate(val track: Track, val score: Double)

data class MatchResult(
    val status: MatchStatus,
    val best: MatchCandidate?,
    val alternatives: List<MatchCandidate>,
)

enum class ImportJobState { RUNNING, COMPLETED, FAILED }

data class ImportJob(
    val id: Long,
    val playlistName: String,
    val format: ImportFormat,
    val state: ImportJobState,
    val total: Int,
    val processed: Int,
    val matched: Int,
    val needsReview: Int,
    val notFound: Int,
    val targetPlaylistId: Long?,
    val error: String?,
    val createdAt: Long,
)

data class ImportItem(
    val id: Long,
    val jobId: Long,
    val position: Int,
    val source: ImportedTrack,
    val result: MatchResult,
)

interface ImportRepository {
    /** Lit la source et lance le matching en tâche de fond (WorkManager). Renvoie les ids des jobs créés. */
    suspend fun start(source: ImportSource): List<Long>
    fun observeJobs(): Flow<List<ImportJob>>
    fun observeJob(jobId: Long): Flow<ImportJob?>
    fun observeItems(jobId: Long): Flow<List<ImportItem>>
    /** Choix manuel d'un candidat (ou null pour exclure le titre). Met à jour la playlist cible. */
    suspend fun resolveItem(itemId: Long, chosen: Track?)
    suspend fun deleteJob(jobId: Long)
}
