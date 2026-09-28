package com.spautifaille.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.repository.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Réglages dans un `DataStore<Preferences>` (fichier « settings »). Les défauts viennent de [AppSettings]. */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_over_wifi_only")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val STREAM_CACHE_MB = intPreferencesKey("stream_cache_size_mb")
        val LAST_FM_API_KEY = stringPreferencesKey("last_fm_api_key")
        val CONTENT_COUNTRY = stringPreferencesKey("content_country")
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setAudioQuality(quality: AudioQuality) {
        dataStore.edit { it[Keys.AUDIO_QUALITY] = quality.name }
    }

    override suspend fun setDownloadOverWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setStreamCacheSizeMb(sizeMb: Int) {
        require(sizeMb > 0) { "La taille du cache doit être positive" }
        dataStore.edit { it[Keys.STREAM_CACHE_MB] = sizeMb }
    }

    override suspend fun setLastFmApiKey(key: String?) {
        val clean = key?.trim()
        dataStore.edit {
            if (clean.isNullOrEmpty()) it.remove(Keys.LAST_FM_API_KEY) else it[Keys.LAST_FM_API_KEY] = clean
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            audioQuality = enumOrNull<AudioQuality>(this[Keys.AUDIO_QUALITY]) ?: defaults.audioQuality,
            downloadOverWifiOnly = this[Keys.DOWNLOAD_WIFI_ONLY] ?: defaults.downloadOverWifiOnly,
            themeMode = enumOrNull<ThemeMode>(this[Keys.THEME_MODE]) ?: defaults.themeMode,
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            streamCacheSizeMb = this[Keys.STREAM_CACHE_MB] ?: defaults.streamCacheSizeMb,
            lastFmApiKey = this[Keys.LAST_FM_API_KEY] ?: defaults.lastFmApiKey,
            contentCountry = this[Keys.CONTENT_COUNTRY] ?: defaults.contentCountry,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }
}
