package com.spautifaille.data.di

import com.spautifaille.data.recognition.AudioRecordCapture
import com.spautifaille.data.recognition.ShazamMusicRecognizer
import com.spautifaille.domain.di.DefaultDispatcher
import com.spautifaille.domain.recognition.AudioCapture
import com.spautifaille.domain.recognition.MusicRecognizer
import com.spautifaille.domain.recognition.RecognizeMusicUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Reconnaissance musicale : capture micro, client Shazam, use case. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecognitionBindingsModule {
    @Binds abstract fun bindAudioCapture(impl: AudioRecordCapture): AudioCapture
}

@Module
@InstallIn(SingletonComponent::class)
object RecognitionModule {
    @Provides
    @Singleton
    fun provideMusicRecognizer(
        client: OkHttpClient,
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): MusicRecognizer = ShazamMusicRecognizer(client, dispatcher)

    @Provides
    fun provideRecognizeMusicUseCase(capture: AudioCapture, recognizer: MusicRecognizer): RecognizeMusicUseCase =
        RecognizeMusicUseCase(capture, recognizer)
}
