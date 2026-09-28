package com.spautifaille.data.di

import com.spautifaille.data.newpipe.NewPipeStreamRepository
import com.spautifaille.data.newpipe.OkHttpDownloader
import com.spautifaille.domain.repository.StreamRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.schabi.newpipe.extractor.downloader.Downloader
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NewPipeModule {

    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: NewPipeStreamRepository): StreamRepository

    @Binds
    @Singleton
    abstract fun bindDownloader(impl: OkHttpDownloader): Downloader
}
