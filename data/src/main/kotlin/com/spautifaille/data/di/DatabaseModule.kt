package com.spautifaille.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.spautifaille.data.local.HistoryDao
import com.spautifaille.data.local.PlaylistDao
import com.spautifaille.data.local.QueueDao
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.SubscriptionDao
import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.configureSpautifaille
import com.spautifaille.domain.di.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SpautifailleDatabase =
        Room.databaseBuilder(context, SpautifailleDatabase::class.java, SpautifailleDatabase.NAME)
            // Callback « Titres likés » + migrations explicites (Migrations.kt). Jamais de repli destructif.
            .configureSpautifaille()
            .build()

    @Provides
    fun provideTrackDao(db: SpautifailleDatabase): TrackDao = db.trackDao()

    @Provides
    fun providePlaylistDao(db: SpautifailleDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideHistoryDao(db: SpautifailleDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideSubscriptionDao(db: SpautifailleDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun provideQueueDao(db: SpautifailleDatabase): QueueDao = db.queueDao()

    /** Une seule instance par fichier : DataStore interdit deux instances actives sur le même fichier. */
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("settings") },
    )
}
