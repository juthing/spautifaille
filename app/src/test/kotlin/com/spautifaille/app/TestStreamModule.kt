package com.spautifaille.app

import com.spautifaille.data.di.NewPipeModule
import com.spautifaille.data.newpipe.OkHttpDownloader
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.ArtistDetails
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.Paged
import com.spautifaille.domain.model.RemotePlaylistPage
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.model.TrackStats
import com.spautifaille.domain.repository.StreamRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import org.schabi.newpipe.extractor.downloader.Downloader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seul remplacement du graphe de production dans le smoke test : aucun appel réseau vers YouTube.
 * Les listes sont vides, les accès à un contenu précis échouent comme hors ligne.
 */
class OfflineStreamRepository @Inject constructor() : StreamRepository {
    override suspend fun suggestions(query: String): List<String> = emptyList()

    override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Paged<SearchResult> =
        Paged(emptyList(), null)

    override suspend fun track(videoId: String): Track = throw AppException(AppError.Network)

    override suspend fun resolveAudio(videoId: String, quality: AudioQuality): ResolvedStream =
        throw AppException(AppError.Network)

    override suspend fun trackStats(videoId: String): TrackStats = throw AppException(AppError.Network)

    override suspend fun related(videoId: String): List<Track> = emptyList()

    override suspend fun mix(videoId: String): List<Track> = emptyList()

    override suspend fun remotePlaylist(url: String, page: PageToken?): RemotePlaylistPage =
        throw AppException(AppError.Network)

    override suspend fun artist(url: String): ArtistDetails = throw AppException(AppError.Network)

    override fun isPlaylistUrl(url: String): Boolean = false
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NewPipeModule::class])
abstract class TestStreamModule {
    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: OfflineStreamRepository): StreamRepository

    // Même liaison que NewPipeModule (le graphe de SpautifailleApp l'exige) ; jamais utilisé sans repository réel.
    @Binds
    @Singleton
    abstract fun bindDownloader(impl: OkHttpDownloader): Downloader
}
