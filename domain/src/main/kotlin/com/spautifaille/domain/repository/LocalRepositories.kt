package com.spautifaille.domain.repository

import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.model.PlayCount
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.model.PlaylistWithTracks
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observePlaylist(id: Long): Flow<PlaylistWithTracks?>
    suspend fun create(name: String, tracks: List<Track> = emptyList()): Long
    suspend fun rename(id: Long, name: String)
    /** Sans effet sur une playlist système. */
    suspend fun delete(id: Long)
    suspend fun addTracks(playlistId: Long, tracks: List<Track>)
    suspend fun removeEntry(playlistId: Long, entryId: Long)
    suspend fun moveEntry(playlistId: Long, fromPosition: Int, toPosition: Int)
    suspend fun containsTrack(playlistId: Long, trackId: String): Boolean
}

interface LibraryRepository {
    fun observeLikedIds(): Flow<Set<String>>
    fun observeIsLiked(trackId: String): Flow<Boolean>
    suspend fun isLiked(trackId: String): Boolean
    /** Like / unlike : ajoute ou retire le titre de la playlist « Titres likés ». Renvoie le nouvel état. */
    suspend fun toggleLike(track: Track): Boolean
    suspend fun setLiked(track: Track, liked: Boolean)

    suspend fun recordPlay(track: Track, playedAt: Long = System.currentTimeMillis())
    fun observeHistory(limit: Int = 500): Flow<List<HistoryEntry>>
    suspend fun removeHistoryEntry(id: Long)
    suspend fun clearHistory()
    suspend fun recentlyPlayedIds(sinceMs: Long): Set<String>
    suspend fun mostPlayed(limit: Int, sinceMs: Long = 0): List<PlayCount>
    suspend fun recentlyLiked(limit: Int): List<Track>
    /** Ids de tous les titres présents dans une playlist locale (likés compris). */
    suspend fun libraryTrackIds(): Set<String>

    fun observeSubscriptions(): Flow<List<Artist>>
    fun observeIsSubscribed(artistUrl: String): Flow<Boolean>
    suspend fun subscribe(artist: Artist)
    suspend fun unsubscribe(artistUrl: String)
}

/** Cache local des métadonnées de titres. */
interface TrackCache {
    suspend fun get(id: String): Track?
    suspend fun put(tracks: List<Track>)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setAudioQuality(quality: AudioQuality)
    suspend fun setDownloadOverWifiOnly(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setStreamCacheSizeMb(sizeMb: Int)
    suspend fun setLastFmApiKey(key: String?)
}

interface DownloadRepository {
    fun observeDownloads(): Flow<List<Download>>
    fun observeDownload(trackId: String): Flow<Download?>
    fun observeStorageUsage(): Flow<StorageUsage>
    suspend fun enqueue(tracks: List<Track>)
    suspend fun cancel(trackId: String)
    suspend fun retry(trackId: String)
    suspend fun delete(trackId: String)
    suspend fun deleteAll()
    /**
     * Chemin absolu du fichier téléchargé et complet, ou null. Appelé depuis le thread de chargement
     * du lecteur : doit être rapide (index en mémoire / requête Room indexée).
     */
    fun localFileBlocking(trackId: String): String?
}
