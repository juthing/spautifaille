package com.spautifaille.data.di

import android.content.Context
import com.spautifaille.data.lyrics.FileLyricsCache
import com.spautifaille.data.lyrics.LrclibClient
import com.spautifaille.data.lyrics.LyricsCache
import com.spautifaille.data.lyrics.LyricsRepositoryImpl
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.repository.LyricsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsBindingsModule {
    @Binds abstract fun bindLyricsRepository(impl: LyricsRepositoryImpl): LyricsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {

    @Provides
    @Singleton
    fun provideLrclibClient(client: OkHttpClient, @IoDispatcher io: CoroutineDispatcher): LrclibClient =
        LrclibClient(client, io)

    @Provides
    @Singleton
    fun provideLyricsCache(@ApplicationContext context: Context): LyricsCache =
        FileLyricsCache(File(context.cacheDir, "lyrics"))
}
