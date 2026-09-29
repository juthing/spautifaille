package com.spautifaille.ui.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.ui.common.ElapsedClock
import com.spautifaille.ui.common.SystemElapsedClock
import com.spautifaille.ui.network.AndroidNetworkMonitor
import com.spautifaille.ui.network.NetworkMonitor
import com.spautifaille.ui.search.DataStoreSearchHistoryRepository
import com.spautifaille.ui.search.SEARCH_HISTORY_DATASTORE_NAME
import com.spautifaille.ui.search.SearchHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Named
import javax.inject.Singleton

/** Liaisons propres à la couche UI (horloge, réseau, historique de recherche). */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiBindingsModule {
    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor

    @Binds
    @Singleton
    abstract fun bindSearchHistoryRepository(impl: DataStoreSearchHistoryRepository): SearchHistoryRepository

    companion object {
        @Provides
        fun provideElapsedClock(): ElapsedClock = SystemElapsedClock

        /** DataStore dédié `search_history` (qualifié : le DataStore non qualifié est celui des réglages, dans `:data`). */
        @Provides
        @Singleton
        @Named(SEARCH_HISTORY_DATASTORE_NAME)
        fun provideSearchHistoryDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(SEARCH_HISTORY_DATASTORE_NAME) },
        )
    }
}
