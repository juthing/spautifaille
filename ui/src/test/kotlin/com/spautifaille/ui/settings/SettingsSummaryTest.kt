package com.spautifaille.ui.settings

import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSummaryTest {

    private fun summary(
        category: SettingsCategory,
        settings: AppSettings = AppSettings(),
        dynamic: Boolean = true,
        version: String = "1.2.3",
    ) = settingsSummary(category, settings, dynamic, version)

    @Test
    fun `appearance summary combines theme and dynamic colors`() {
        val dark = AppSettings(themeMode = ThemeMode.DARK, colorSource = ColorSource.DYNAMIC)
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_theme_dark), SummaryPart.Text(R.string.set_summary_dynamic_color)),
            summary(SettingsCategory.APPEARANCE, dark),
        )
    }

    @Test
    fun `appearance summary omits dynamic colors when disabled or unavailable`() {
        val light = AppSettings(themeMode = ThemeMode.LIGHT, colorSource = ColorSource.STATIC)
        assertEquals(listOf(SummaryPart.Text(R.string.set_summary_theme_light)), summary(SettingsCategory.APPEARANCE, light))

        val system = AppSettings(themeMode = ThemeMode.SYSTEM, colorSource = ColorSource.DYNAMIC)
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_theme_system)),
            summary(SettingsCategory.APPEARANCE, system, dynamic = false),
        )
    }

    @Test
    fun `appearance summary names the now playing color source`() {
        val nowPlaying = AppSettings(themeMode = ThemeMode.DARK, colorSource = ColorSource.NOW_PLAYING)
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_theme_dark), SummaryPart.Text(R.string.set_summary_now_playing_color)),
            summary(SettingsCategory.APPEARANCE, nowPlaying, dynamic = false),
        )
    }

    @Test
    fun `playback summary names the audio quality`() {
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_quality_best)),
            summary(SettingsCategory.PLAYBACK, AppSettings(audioQuality = AudioQuality.BEST)),
        )
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_quality_data_saver)),
            summary(SettingsCategory.PLAYBACK, AppSettings(audioQuality = AudioQuality.DATA_SAVER)),
        )
    }

    @Test
    fun `downloads summary reflects the wifi only switch`() {
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_wifi_only)),
            summary(SettingsCategory.DOWNLOADS, AppSettings(downloadOverWifiOnly = true)),
        )
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_wifi_and_mobile)),
            summary(SettingsCategory.DOWNLOADS, AppSettings(downloadOverWifiOnly = false)),
        )
    }

    @Test
    fun `storage summary carries the cache size`() {
        assertEquals(
            listOf(SummaryPart.CacheSize(1024)),
            summary(SettingsCategory.STORAGE, AppSettings(streamCacheSizeMb = 1024)),
        )
    }

    @Test
    fun `discovery summary reflects the last fm key and ignores blank keys`() {
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_lastfm_on)),
            summary(SettingsCategory.DISCOVERY, AppSettings(lastFmApiKey = "abc")),
        )
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_lastfm_off)),
            summary(SettingsCategory.DISCOVERY, AppSettings(lastFmApiKey = " ")),
        )
        assertEquals(
            listOf(SummaryPart.Text(R.string.set_summary_lastfm_off)),
            summary(SettingsCategory.DISCOVERY, AppSettings(lastFmApiKey = null)),
        )
    }

    @Test
    fun `about summary shows version then license`() {
        assertEquals(
            listOf(SummaryPart.Version("1.2.3"), SummaryPart.Text(R.string.set_summary_license)),
            summary(SettingsCategory.ABOUT),
        )
    }

    @Test
    fun `every category has a non empty summary`() {
        SettingsCategory.entries.forEach { assertTrue(it.name, summary(it).isNotEmpty()) }
    }

    @Test
    fun `route keys round trip for categories with a subpage`() {
        SettingsCategory.entries.filter { it.opensSubpage }.forEach {
            assertEquals(it, SettingsCategory.fromKey(it.key))
        }
    }

    @Test
    fun `import has no subpage and unknown keys are rejected`() {
        assertNull(SettingsCategory.fromKey(SettingsCategory.IMPORT.key))
        assertNull(SettingsCategory.fromKey("nope"))
        assertNull(SettingsCategory.fromKey(""))
    }
}
