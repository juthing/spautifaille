package com.spautifaille.data.di

import com.spautifaille.data.repository.LibraryRepositoryImpl
import com.spautifaille.data.repository.PlaylistRepositoryImpl
import com.spautifaille.data.repository.QueueStateStoreImpl
import com.spautifaille.data.repository.SettingsRepositoryImpl
import com.spautifaille.data.repository.TrackCacheImpl
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.domain.repository.QueueStateStore
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.domain.repository.TrackCache
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository
    @Binds abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository
    @Binds abstract fun bindTrackCache(impl: TrackCacheImpl): TrackCache
    @Binds abstract fun bindQueueStateStore(impl: QueueStateStoreImpl): QueueStateStore
    @Binds abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
