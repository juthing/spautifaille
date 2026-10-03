package com.spautifaille.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.ui.R
import com.spautifaille.ui.components.IconTone

/**
 * Catégories de la page d'accueil des réglages. [IMPORT] et [YOUTUBE] n'ont pas de sous-page de réglages : elles
 * ouvrent directement leur propre écran (import ; compte YouTube), voir [opensSubpage].
 */
enum class SettingsCategory(
    @StringRes val title: Int,
    val icon: ImageVector,
    val tone: IconTone,
    val opensSubpage: Boolean = true,
) {
    PLAYBACK(R.string.set_cat_playback, Icons.Filled.GraphicEq, IconTone.Primary),
    DOWNLOADS(R.string.set_cat_downloads, Icons.Filled.Download, IconTone.Tertiary),
    IMPORT(R.string.set_cat_import, Icons.AutoMirrored.Filled.PlaylistAdd, IconTone.Secondary, opensSubpage = false),
    YOUTUBE(R.string.set_cat_youtube, Icons.Filled.AccountCircle, IconTone.Tertiary, opensSubpage = false),
    APPEARANCE(R.string.set_cat_appearance, Icons.Filled.Palette, IconTone.Primary),
    STORAGE(R.string.set_cat_storage, Icons.Filled.Storage, IconTone.Secondary),
    DISCOVERY(R.string.set_cat_discovery, Icons.Filled.AutoAwesome, IconTone.Tertiary),
    ABOUT(R.string.set_cat_about, Icons.Filled.Info, IconTone.Neutral),
    ;

    /** Clé stable portée par `SettingsCategoryRoute`. */
    val key: String get() = name

    companion object {
        /** Catégorie à sous-page correspondant à [key], ou `null` (clé inconnue ou catégorie sans sous-page). */
        fun fromKey(key: String): SettingsCategory? =
            entries.firstOrNull { it.key == key && it.opensSubpage }
    }
}

/** Fragment du résumé d'une catégorie, résolu en texte par l'UI (ressources : pas d'accès `Context` ici). */
sealed interface SummaryPart {
    data class Text(@StringRes val res: Int) : SummaryPart

    /** « Cache de lecture : 512 Mo ». */
    data class CacheSize(val sizeMb: Int) : SummaryPart

    /** Nom de version ; vide = inconnue. */
    data class Version(val name: String) : SummaryPart
}

/**
 * Résumé d'une catégorie (« Thème sombre · Couleurs dynamiques »), en fragments que l'UI joint par « · ».
 * Fonction pure : testable sans Android.
 */
fun settingsSummary(
    category: SettingsCategory,
    settings: AppSettings,
    dynamicColorAvailable: Boolean,
    versionName: String,
): List<SummaryPart> = when (category) {
    SettingsCategory.PLAYBACK -> listOf(
        SummaryPart.Text(
            when (settings.audioQuality) {
                AudioQuality.BEST -> R.string.set_quality_best
                AudioQuality.DATA_SAVER -> R.string.set_quality_data_saver
            },
        ),
    )
    SettingsCategory.DOWNLOADS -> listOf(
        SummaryPart.Text(
            if (settings.downloadOverWifiOnly) R.string.set_summary_wifi_only else R.string.set_summary_wifi_and_mobile,
        ),
    )
    SettingsCategory.IMPORT -> listOf(SummaryPart.Text(R.string.set_summary_import))
    SettingsCategory.YOUTUBE -> listOf(SummaryPart.Text(R.string.set_summary_youtube))
    SettingsCategory.APPEARANCE -> buildList {
        add(
            SummaryPart.Text(
                when (settings.themeMode) {
                    ThemeMode.SYSTEM -> R.string.set_summary_theme_system
                    ThemeMode.LIGHT -> R.string.set_summary_theme_light
                    ThemeMode.DARK -> R.string.set_summary_theme_dark
                },
            ),
        )
        when (settings.colorSource) {
            ColorSource.STATIC -> Unit
            ColorSource.DYNAMIC -> if (dynamicColorAvailable) add(SummaryPart.Text(R.string.set_summary_dynamic_color))
            ColorSource.NOW_PLAYING -> add(SummaryPart.Text(R.string.set_summary_now_playing_color))
        }
    }
    SettingsCategory.STORAGE -> listOf(SummaryPart.CacheSize(settings.streamCacheSizeMb))
    SettingsCategory.DISCOVERY -> listOf(
        SummaryPart.Text(
            if (settings.lastFmApiKey.isNullOrBlank()) R.string.set_summary_lastfm_off else R.string.set_summary_lastfm_on,
        ),
    )
    SettingsCategory.ABOUT -> listOf(SummaryPart.Version(versionName), SummaryPart.Text(R.string.set_summary_license))
}
