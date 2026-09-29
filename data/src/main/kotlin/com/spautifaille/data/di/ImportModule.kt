package com.spautifaille.data.di

import com.spautifaille.data.importer.FilePlaylistImporter
import com.spautifaille.data.importer.ImportRepositoryImpl
import com.spautifaille.data.importer.ImportScheduler
import com.spautifaille.data.importer.WorkManagerImportScheduler
import com.spautifaille.data.importer.YouTubePlaylistImporter
import com.spautifaille.domain.importer.ImportRepository
import com.spautifaille.domain.importer.PlaylistImporter
import com.spautifaille.domain.matching.TrackMatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ImportModule {

    @Binds
    abstract fun bindImportRepository(impl: ImportRepositoryImpl): ImportRepository

    @Binds
    abstract fun bindImportScheduler(impl: WorkManagerImportScheduler): ImportScheduler

    companion object {
        @Provides
        @Singleton
        fun provideTrackMatcher(): TrackMatcher = TrackMatcher()

        /** Ordre = priorité : le premier importer qui reconnaît la source la lit. */
        @Provides
        fun provideImporters(
            youtube: YouTubePlaylistImporter,
            file: FilePlaylistImporter,
        ): List<@JvmSuppressWildcards PlaylistImporter> = listOf(youtube, file)
    }
}
