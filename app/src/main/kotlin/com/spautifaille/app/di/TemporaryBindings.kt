package com.spautifaille.app.di

import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.DownloadRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Implémentation neutre en attendant le jalon J5 (téléchargements). À supprimer à l'intégration de J5. */
@Singleton
class NoDownloadRepository @Inject constructor() : DownloadRepository {
    override fun observeDownloads(): Flow<List<Download>> = flowOf(emptyList())
    override fun observeDownload(trackId: String): Flow<Download?> = flowOf(null)
    override fun observeStorageUsage(): Flow<StorageUsage> = flowOf(StorageUsage(0, 0, 0))
    override suspend fun enqueue(tracks: List<Track>) = Unit
    override suspend fun cancel(trackId: String) = Unit
    override suspend fun retry(trackId: String) = Unit
    override suspend fun delete(trackId: String) = Unit
    override suspend fun deleteAll() = Unit
    override fun localFileBlocking(trackId: String): String? = null
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TemporaryBindingsModule {
    @Binds abstract fun downloadRepository(impl: NoDownloadRepository): DownloadRepository
}
