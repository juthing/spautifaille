package com.spautifaille.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: SettingsRepositoryImpl

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "settings.preferences_pb")
        }
        repo = SettingsRepositoryImpl(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsComeFromAppSettings() = runTest {
        assertEquals(AppSettings(), repo.current())
    }

    @Test
    fun roundTripOfEverySetting() = runTest {
        repo.setAudioQuality(AudioQuality.DATA_SAVER)
        repo.setDownloadOverWifiOnly(false)
        repo.setThemeMode(ThemeMode.DARK)
        repo.setColorSource(ColorSource.NOW_PLAYING)
        repo.setStreamCacheSizeMb(2048)
        repo.setLastFmApiKey("  abc123 ")

        assertEquals(
            AppSettings(
                audioQuality = AudioQuality.DATA_SAVER,
                downloadOverWifiOnly = false,
                themeMode = ThemeMode.DARK,
                colorSource = ColorSource.NOW_PLAYING,
                streamCacheSizeMb = 2048,
                lastFmApiKey = "abc123",
            ),
            repo.current(),
        )
    }

    @Test
    fun settingsFlowEmitsOnChangeOnly() = runTest {
        repo.settings.test {
            assertEquals(AppSettings(), awaitItem())
            repo.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, awaitItem().themeMode)
            repo.setThemeMode(ThemeMode.LIGHT) // inchangé : pas d'émission
            repo.setColorSource(ColorSource.NOW_PLAYING)
            assertEquals(ColorSource.NOW_PLAYING, awaitItem().colorSource)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun blankOrNullLastFmKeyIsRemoved() = runTest {
        repo.setLastFmApiKey("key")
        repo.setLastFmApiKey("  ")
        assertNull(repo.current().lastFmApiKey)

        repo.setLastFmApiKey("key")
        repo.setLastFmApiKey(null)
        assertNull(repo.current().lastFmApiKey)
    }

    @Test
    fun unknownEnumValuesFallBackToDefaults() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("audio_quality")] = "SUPER_HD"
            it[stringPreferencesKey("theme_mode")] = "SEPIA"
        }
        val defaults = AppSettings()
        assertEquals(defaults.audioQuality, repo.current().audioQuality)
        assertEquals(defaults.themeMode, repo.current().themeMode)
    }

    @Test
    fun invalidCacheSizeIsRejected() = runTest {
        val result = runCatching { repo.setStreamCacheSizeMb(0) }
        assertEquals(true, result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(AppSettings().streamCacheSizeMb, repo.current().streamCacheSizeMb)
    }

    @Test
    fun legacyDynamicColorTrueMigratesToDynamic() = runTest {
        dataStore.edit { it[booleanPreferencesKey("dynamic_color")] = true }
        assertEquals(ColorSource.DYNAMIC, repo.current().colorSource)
    }

    @Test
    fun legacyDynamicColorFalseMigratesToStatic() = runTest {
        dataStore.edit { it[booleanPreferencesKey("dynamic_color")] = false }
        assertEquals(ColorSource.STATIC, repo.current().colorSource)
    }

    @Test
    fun newColorSourceWinsOverLegacyValueAndClearsIt() = runTest {
        dataStore.edit { it[booleanPreferencesKey("dynamic_color")] = false }
        repo.setColorSource(ColorSource.NOW_PLAYING)
        assertEquals(ColorSource.NOW_PLAYING, repo.current().colorSource)
        assertNull(dataStore.data.first()[booleanPreferencesKey("dynamic_color")])
    }

    @Test
    fun unknownColorSourceFallsBackToLegacyThenDefault() = runTest {
        dataStore.edit { it[stringPreferencesKey("color_source")] = "RAINBOW" }
        assertEquals(AppSettings().colorSource, repo.current().colorSource)
        dataStore.edit { it[booleanPreferencesKey("dynamic_color")] = false }
        assertEquals(ColorSource.STATIC, repo.current().colorSource)
    }
}
