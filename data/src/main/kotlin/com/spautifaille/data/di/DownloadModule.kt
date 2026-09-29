package com.spautifaille.data.di

import android.content.Context
import androidx.work.WorkManager
import com.spautifaille.data.download.DownloadRepositoryImpl
import com.spautifaille.domain.repository.DownloadRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    companion object {
        /**
         * WorkManager doit être initialisé par l'application avec une `HiltWorkerFactory`
         * (`Configuration.Provider`) avant la première injection de [DownloadRepository].
         */
        @Provides
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
    }
}
