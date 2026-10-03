package com.spautifaille.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.YouTubeSyncDao
import com.spautifaille.data.youtube.YouTubeAccountRepository
import com.spautifaille.data.youtube.YouTubeLibrarySync
import com.spautifaille.data.youtube.YouTubePlaylistLinks
import com.spautifaille.data.youtube.api.InnerTubeYouTubeRemote
import com.spautifaille.data.youtube.api.YouTubeRemote
import com.spautifaille.data.youtube.session.AesGcmSecretCipher
import com.spautifaille.data.youtube.session.AndroidKeystoreKeys
import com.spautifaille.data.youtube.session.DataStoreSyncMetaStore
import com.spautifaille.data.youtube.session.DataStoreYouTubeSessionStore
import com.spautifaille.data.youtube.session.SecretCipher
import com.spautifaille.data.youtube.session.SyncMetaStore
import com.spautifaille.data.youtube.session.YouTubeDataStore
import com.spautifaille.data.youtube.session.YouTubeSessionStore
import com.spautifaille.data.youtube.sync.PendingActionRecorder
import com.spautifaille.data.youtube.sync.RemoteSyncRecorder
import com.spautifaille.data.youtube.sync.SyncScheduler
import com.spautifaille.data.youtube.sync.WorkManagerSyncScheduler
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.youtube.AccountRepository
import com.spautifaille.domain.youtube.LibrarySync
import com.spautifaille.domain.youtube.YouTubePlaylistSync
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Compte YouTube : session chiffrée, client InnerTube, file d'actions et synchronisation. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class YouTubeModule {
    @Binds abstract fun bindSessionStore(impl: DataStoreYouTubeSessionStore): YouTubeSessionStore
    @Binds abstract fun bindSyncMetaStore(impl: DataStoreSyncMetaStore): SyncMetaStore
    @Binds abstract fun bindRemote(impl: InnerTubeYouTubeRemote): YouTubeRemote
    @Binds abstract fun bindScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
    @Binds abstract fun bindRecorder(impl: PendingActionRecorder): RemoteSyncRecorder
    @Binds abstract fun bindAccountRepository(impl: YouTubeAccountRepository): AccountRepository
    @Binds abstract fun bindLibrarySync(impl: YouTubeLibrarySync): LibrarySync
    @Binds abstract fun bindPlaylistSync(impl: YouTubePlaylistLinks): YouTubePlaylistSync

    companion object {
        private const val KEY_ALIAS = "spautifaille_youtube_session"

        @Provides
        fun provideYouTubeSyncDao(db: SpautifailleDatabase): YouTubeSyncDao = db.youTubeSyncDao()

        @Provides
        @Singleton
        fun provideSecretCipher(): SecretCipher = AesGcmSecretCipher(AndroidKeystoreKeys.secretKey(KEY_ALIAS))

        /** Fichier distinct de `settings` : exclu des sauvegardes (la clé Keystore ne suit pas l'appareil). */
        @Provides
        @Singleton
        @YouTubeDataStore
        fun provideYouTubeDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("youtube_account") },
        )
    }
}
