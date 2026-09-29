package com.spautifaille.data.importer

import com.spautifaille.data.local.ImportItemEntity
import com.spautifaille.data.local.ImportJobEntity
import com.spautifaille.data.local.TrackEntity
import com.spautifaille.data.local.toDomain
import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportItem
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.MatchCandidate
import com.spautifaille.domain.importer.MatchResult
import com.spautifaille.domain.importer.MatchStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Séparateur des artistes dans `import_items.source_artists` (U+001F). */
internal const val ARTIST_SEPARATOR = '\u001F'

/** Entrée du JSON `import_items.candidates` : `{"trackId":"…","score":0.93}`, trié par score décroissant. */
@Serializable
internal data class CandidateJson(val trackId: String, val score: Double)

private val json = Json { ignoreUnknownKeys = true }
private val candidatesSerializer = ListSerializer(CandidateJson.serializer())

internal fun encodeCandidates(candidates: List<CandidateJson>): String =
    json.encodeToString(candidatesSerializer, candidates)

internal fun decodeCandidates(raw: String?): List<CandidateJson> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        json.decodeFromString(candidatesSerializer, raw)
    } catch (e: Exception) {
        emptyList()
    }
}

internal fun ImportedTrack.toItemEntity(jobId: Long, position: Int) = ImportItemEntity(
    jobId = jobId,
    position = position,
    sourceTitle = title,
    sourceArtists = artists.joinToString(ARTIST_SEPARATOR.toString()),
    sourceAlbum = album,
    sourceDurationMs = durationMs,
    sourceIsrc = isrc,
    sourceYoutubeId = youtubeId,
    status = MatchStatus.PENDING.name,
    bestTrackId = null,
    bestScore = null,
    candidates = null,
    entryId = null,
)

internal fun ImportItemEntity.toImportedTrack() = ImportedTrack(
    title = sourceTitle,
    artists = sourceArtists.split(ARTIST_SEPARATOR).filter { it.isNotBlank() },
    album = sourceAlbum,
    durationMs = sourceDurationMs,
    isrc = sourceIsrc,
    youtubeId = sourceYoutubeId,
)

internal fun ImportJobEntity.toDomain() = ImportJob(
    id = id,
    playlistName = playlistName,
    format = ImportFormat.entries.firstOrNull { it.name == format } ?: ImportFormat.GENERIC_CSV,
    state = ImportJobState.entries.firstOrNull { it.name == state } ?: ImportJobState.FAILED,
    total = total,
    processed = processed,
    matched = matched,
    needsReview = needsReview,
    notFound = notFound,
    targetPlaylistId = targetPlaylistId,
    error = error,
    createdAt = createdAt,
)

internal fun ImportItemEntity.candidateIds(): List<String> =
    (decodeCandidates(candidates).map { it.trackId } + listOfNotNull(bestTrackId)).distinct()

/** Reconstitue l'item du domaine ; les candidats dont le titre n'est plus en cache sont ignorés. */
internal fun ImportItemEntity.toDomain(tracks: Map<String, TrackEntity>): ImportItem {
    val all = decodeCandidates(candidates).mapNotNull { c ->
        tracks[c.trackId]?.let { MatchCandidate(it.toDomain(), c.score) }
    }
    val bestId = bestTrackId
    val best = bestId?.let { id ->
        all.firstOrNull { it.track.id == id }
            ?: tracks[id]?.let { MatchCandidate(it.toDomain(), bestScore ?: 0.0) }
    }
    val status = MatchStatus.entries.firstOrNull { it.name == status } ?: MatchStatus.NOT_FOUND
    return ImportItem(
        id = id,
        jobId = jobId,
        position = position,
        source = toImportedTrack(),
        result = MatchResult(status, best, all.filter { it.track.id != best?.track?.id }),
    )
}
