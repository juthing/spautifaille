package com.spautifaille.ui.settings

import app.cash.turbine.test
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.ui.library.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val settings = MutableStateFlow(AppSettings())
    private val repository = mockk<SettingsRepository>(relaxed = true) {
        every { this@mockk.settings } returns this@SettingsViewModelTest.settings
    }

    private fun viewModel() = SettingsViewModel(repository)

    @Test
    fun `initial state is not loaded then mirrors the repository`() = runTest {
        val vm = viewModel()
        assertFalse(vm.uiState.value.isLoaded)
        vm.uiState.test {
            var loaded = awaitItem()
            if (!loaded.isLoaded) loaded = awaitItem()
            assertTrue(loaded.isLoaded)
            assertEquals(AppSettings(), loaded.settings)

            settings.value = AppSettings(themeMode = ThemeMode.DARK, streamCacheSizeMb = 1024)
            val updated = awaitItem()
            assertEquals(ThemeMode.DARK, updated.settings.themeMode)
            assertEquals(1024, updated.settings.streamCacheSizeMb)
        }
    }

    @Test
    fun `setters are forwarded to the repository`() = runTest {
        val vm = viewModel()

        vm.setAudioQuality(AudioQuality.DATA_SAVER)
        vm.setDownloadOverWifiOnly(false)
        vm.setThemeMode(ThemeMode.LIGHT)
        vm.setColorSource(ColorSource.NOW_PLAYING)
        vm.setStreamCacheSizeMb(2048)

        coVerify { repository.setAudioQuality(AudioQuality.DATA_SAVER) }
        coVerify { repository.setDownloadOverWifiOnly(false) }
        coVerify { repository.setThemeMode(ThemeMode.LIGHT) }
        coVerify { repository.setColorSource(ColorSource.NOW_PLAYING) }
        coVerify { repository.setStreamCacheSizeMb(2048) }
    }

    @Test
    fun `non positive cache size is ignored`() = runTest {
        val vm = viewModel()
        vm.setStreamCacheSizeMb(0)
        vm.setStreamCacheSizeMb(-5)
        coVerify(exactly = 0) { repository.setStreamCacheSizeMb(any()) }
    }

    @Test
    fun `last fm key is trimmed and blank clears it`() = runTest {
        val vm = viewModel()

        vm.setLastFmApiKey("  abc123  ")
        vm.setLastFmApiKey("   ")
        vm.setLastFmApiKey(null)

        coVerify(exactly = 1) { repository.setLastFmApiKey("abc123") }
        coVerify(exactly = 2) { repository.setLastFmApiKey(null) }
    }

    @Test
    fun `hasLastFmKey reflects the stored key without exposing it as configured when blank`() {
        assertFalse(SettingsUiState(settings = AppSettings(lastFmApiKey = null)).hasLastFmKey)
        assertFalse(SettingsUiState(settings = AppSettings(lastFmApiKey = " ")).hasLastFmKey)
        assertTrue(SettingsUiState(settings = AppSettings(lastFmApiKey = "k")).hasLastFmKey)
    }

    @Test
    fun `cache size options are the four documented choices`() {
        assertEquals(listOf(256, 512, 1024, 2048), SettingsViewModel.CacheSizeOptionsMb)
    }
}
