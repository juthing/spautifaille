package com.spautifaille.data.local

import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.PlayCount
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistEntry
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.RepeatMode
import com.spautifaille.domain.repository.QueueSnapshot

fun TrackEntity.toDomain() = Track(
    id = id,
    title = title,
    artist = artist,
    artistUrl = artistUrl,
    album = album,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
)

fun Track.toEntity(updatedAt: Long) = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    artistUrl = artistUrl,
    album = album,
    durationMs = durationMs,
    thumbnailUrl = thumbnailUrl,
    updatedAt = updatedAt,
)

fun PlaylistRow.toDomain(trackCount: Int = this.trackCount) = Playlist(
    id = id,
    name = name,
    trackCount = trackCount,
    thumbnailUrl = thumbnailUrl,
    isSystem = isSystem,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun EntryWithTrack.toDomain() = PlaylistEntry(entryId = entryId, position = position, track = track.toDomain())

fun HistoryWithTrack.toDomain() = HistoryEntry(id = historyId, track = track.toDomain(), playedAt = playedAt)

fun PlayCountRow.toDomain() = PlayCount(track = track.toDomain(), count = playCount, lastPlayedAt = lastPlayedAt)

fun SubscriptionEntity.toDomain() = Artist(url = url, name = name, avatarUrl = avatarUrl)

fun Artist.toEntity(subscribedAt: Long) = SubscriptionEntity(
    url = url,
    name = name,
    avatarUrl = avatarUrl,
    subscribedAt = subscribedAt,
)

fun QueueSnapshot.toStateEntity() = QueueStateEntity(
    currentIndex = currentIndex,
    positionMs = positionMs,
    shuffleEnabled = shuffleEnabled,
    repeatMode = repeatMode.name,
    shuffleOrder = shuffleOrder.joinToString(","),
)

fun QueueStateEntity.toSnapshot(tracks: List<Track>) = QueueSnapshot(
    tracks = tracks,
    currentIndex = currentIndex,
    positionMs = positionMs,
    shuffleEnabled = shuffleEnabled,
    repeatMode = RepeatMode.entries.firstOrNull { it.name == repeatMode } ?: RepeatMode.OFF,
    shuffleOrder = shuffleOrder.split(',').mapNotNull { it.trim().toIntOrNull() },
)
