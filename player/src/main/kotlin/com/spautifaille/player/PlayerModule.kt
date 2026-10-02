package com.spautifaille.player

import android.content.Context
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.OfflineAvailability
import com.spautifaille.player.datasource.CachedOfflineAvailability
import com.spautifaille.player.error.AndroidConnectivityObserver
import com.spautifaille.player.error.ConnectivityObserver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Câblage Hilt du module lecture.
 *
 * Les classes `@Singleton @Inject constructor` (`StreamResolver`, `MediaCache`, `PlayerDataSourceFactory`,
 * `PlaybackControllerImpl`) se câblent seules. Pré-requis fournis par `:app` / `:data` :
 * `OkHttpClient`, `@ApplicationScope CoroutineScope`, et les implémentations des interfaces de `:domain`
 * (`StreamRepository`, `LibraryRepository`, `PlaylistRepository`, `DownloadRepository`, `SettingsRepository`,
 * `TrackCache`, `QueueStateStore`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackController(impl: PlaybackControllerImpl): PlaybackController

    @Binds
    @Singleton
    abstract fun bindOfflineAvailability(impl: CachedOfflineAvailability): OfflineAvailability

    companion object {
        @Provides
        @Singleton
        fun provideConnectivityObserver(@ApplicationContext context: Context): ConnectivityObserver =
            AndroidConnectivityObserver(context)
    }
}
