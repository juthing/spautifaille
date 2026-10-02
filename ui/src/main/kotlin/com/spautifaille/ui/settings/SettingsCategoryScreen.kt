package com.spautifaille.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.IconTone
import com.spautifaille.ui.components.ToneIconCircle
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

@Immutable
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onAudioQualityChange: (AudioQuality) -> Unit = {},
    val onWifiOnlyChange: (Boolean) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onColorSourceChange: (ColorSource) -> Unit = {},
    val onCacheSizeChange: (Int) -> Unit = {},
    val onLastFmKeyChange: (String?) -> Unit = {},
)

/**
 * Sous-page d'une catégorie de réglages. [categoryKey] vient de `SettingsCategoryRoute` ; une clé inconnue
 * (ou sans sous-page) ramène à la page précédente.
 */
@Composable
fun SettingsCategoryRoute(
    categoryKey: String,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val category = remember(categoryKey) { SettingsCategory.fromKey(categoryKey) }
    if (category == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = remember(context) { context.appVersionName() }
    val actions = remember(viewModel, onBack, onOpenDownloads) {
        SettingsActions(
            onBack = onBack,
            onOpenDownloads = onOpenDownloads,
            onAudioQualityChange = viewModel::setAudioQuality,
            onWifiOnlyChange = viewModel::setDownloadOverWifiOnly,
            onThemeModeChange = viewModel::setThemeMode,
            onColorSourceChange = viewModel::setColorSource,
            onCacheSizeChange = viewModel::setStreamCacheSizeMb,
            onLastFmKeyChange = viewModel::setLastFmApiKey,
        )
    }
    SettingsCategoryScreen(
        category = category,
        state = state,
        actions = actions,
        versionName = versionName,
        dynamicColorAvailable = dynamicColorSupported(),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    state: SettingsUiState,
    actions: SettingsActions,
    versionName: String,
    dynamicColorAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ListBottomPadding),
            ) {
                when (category) {
                    SettingsCategory.PLAYBACK -> PlaybackSettings(state.settings, actions)
                    SettingsCategory.DOWNLOADS -> DownloadsSettings(state.settings, actions)
                    SettingsCategory.APPEARANCE -> AppearanceSettings(state.settings, actions, dynamicColorAvailable)
                    SettingsCategory.STORAGE -> StorageSettings(state.settings, actions)
                    SettingsCategory.DISCOVERY -> DiscoverySettings(state, actions)
                    SettingsCategory.ABOUT -> AboutSettings(versionName)
                    // Pas de sous-page : la navigation n'y mène jamais.
                    SettingsCategory.IMPORT -> Unit
                }
            }
        }
    }
}

@Composable
private fun PlaybackSettings(settings: AppSettings, actions: SettingsActions) {
    SettingsGroupLabel(stringResource(R.string.set_section_quality))
    SettingsRadioGroup(
        options = AudioQuality.entries,
        selected = settings.audioQuality,
        label = { stringResource(it.labelRes()) },
        description = { stringResource(it.descriptionRes()) },
        onSelect = actions.onAudioQualityChange,
    )
}

@Composable
private fun DownloadsSettings(settings: AppSettings, actions: SettingsActions) {
    SettingsGroupLabel(stringResource(R.string.set_section_network))
    SettingsSwitchRow(
        icon = Icons.Filled.Wifi,
        title = stringResource(R.string.set_wifi_only),
        summary = stringResource(R.string.set_wifi_only_summary),
        checked = settings.downloadOverWifiOnly,
        onCheckedChange = actions.onWifiOnlyChange,
        tone = IconTone.Tertiary,
    )
    SettingsGroupLabel(stringResource(R.string.set_section_manage))
    SettingsNavRow(
        icon = Icons.Filled.Download,
        title = stringResource(R.string.set_manage_downloads),
        summary = stringResource(R.string.set_manage_downloads_summary),
        onClick = actions.onOpenDownloads,
        tone = IconTone.Tertiary,
    )
}

@Composable
private fun AppearanceSettings(settings: AppSettings, actions: SettingsActions, dynamicColorAvailable: Boolean) {
    SettingsGroupLabel(stringResource(R.string.set_section_theme))
    SettingsRadioGroup(
        options = ThemeMode.entries,
        selected = settings.themeMode,
        label = { stringResource(it.labelRes()) },
        description = { mode -> if (mode == ThemeMode.SYSTEM) stringResource(R.string.set_theme_system_desc) else null },
        onSelect = actions.onThemeModeChange,
    )
    SettingsGroupLabel(stringResource(R.string.set_section_colors))
    SettingsRadioGroup(
        options = ColorSource.entries,
        // Sous Android 11, « Dynamique » n'existe pas : le thème appliqué est alors « Normal ».
        selected = settings.colorSource.effectiveIn(dynamicColorAvailable),
        label = { stringResource(it.labelRes()) },
        description = { source -> stringResource(source.descriptionRes(dynamicColorAvailable)) },
        isEnabled = { source -> source != ColorSource.DYNAMIC || dynamicColorAvailable },
        onSelect = actions.onColorSourceChange,
    )
    Spacer(Modifier.height(Spacing.s))
    SettingsNote(stringResource(R.string.set_color_source_note))
}

/** Option mise en avant : sans couleurs dynamiques (Android 11), le choix « Dynamique » équivaut à « Normal ». */
internal fun ColorSource.effectiveIn(dynamicColorAvailable: Boolean): ColorSource =
    if (this == ColorSource.DYNAMIC && !dynamicColorAvailable) ColorSource.STATIC else this

@StringRes
private fun ColorSource.labelRes(): Int = when (this) {
    ColorSource.STATIC -> R.string.set_color_source_static
    ColorSource.DYNAMIC -> R.string.set_color_source_dynamic
    ColorSource.NOW_PLAYING -> R.string.set_color_source_now_playing
}

@StringRes
private fun ColorSource.descriptionRes(dynamicColorAvailable: Boolean): Int = when (this) {
    ColorSource.STATIC -> R.string.set_color_source_static_desc
    ColorSource.DYNAMIC ->
        if (dynamicColorAvailable) R.string.set_color_source_dynamic_desc else R.string.set_color_source_dynamic_unavailable
    ColorSource.NOW_PLAYING -> R.string.set_color_source_now_playing_desc
}

@Composable
private fun StorageSettings(settings: AppSettings, actions: SettingsActions) {
    SettingsGroupLabel(stringResource(R.string.set_section_cache))
    SettingsRadioGroup(
        options = SettingsViewModel.CacheSizeOptionsMb,
        selected = settings.streamCacheSizeMb,
        label = { formatCacheSize(it) },
        onSelect = actions.onCacheSizeChange,
    )
    Spacer(Modifier.height(Spacing.s))
    SettingsNote(stringResource(R.string.set_cache_note))
}

@Composable
private fun DiscoverySettings(state: SettingsUiState, actions: SettingsActions) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    SettingsNote(stringResource(R.string.set_lastfm_intro), Modifier.padding(top = Spacing.s))
    SettingsGroupLabel(stringResource(R.string.set_cat_discovery))
    SettingsNavRow(
        icon = Icons.Filled.Key,
        title = stringResource(R.string.set_lastfm_key),
        summary = stringResource(
            if (state.hasLastFmKey) R.string.set_lastfm_configured else R.string.set_lastfm_not_configured,
        ),
        onClick = { showDialog = true },
        tone = IconTone.Tertiary,
        showChevron = false,
    )
    if (showDialog) {
        LastFmKeyDialog(
            currentKey = state.settings.lastFmApiKey.orEmpty(),
            onConfirm = {
                actions.onLastFmKeyChange(it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun AboutSettings(versionName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ToneIconCircle(icon = Icons.Filled.MusicNote, tone = IconTone.Primary, size = 72.dp)
        Spacer(Modifier.height(Spacing.s))
        Text(stringResource(R.string.set_about_app_name), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.set_about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsInfoRow(
        icon = Icons.Filled.Info,
        title = stringResource(R.string.set_about_version),
        summary = versionName.ifBlank { stringResource(R.string.set_about_version_unknown) },
    )
    SettingsInfoRow(
        icon = Icons.Filled.Gavel,
        title = stringResource(R.string.set_about_license),
        summary = stringResource(R.string.set_about_license_value),
    )
    SettingsGroupLabel(stringResource(R.string.set_about_credits))
    SettingsInfoRow(
        icon = Icons.Filled.MusicNote,
        title = stringResource(R.string.set_about_powered_by),
        summary = stringResource(R.string.set_about_powered_by_value),
    )
}

@Composable
private fun LastFmKeyDialog(currentKey: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalAppHaptics.current
    var key by rememberSaveable { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_lastfm_key)) },
        text = {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.set_lastfm_key_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = { Text(stringResource(R.string.set_lastfm_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.confirm()
                    onConfirm(key.trim().ifEmpty { null })
                },
            ) { Text(stringResource(R.string.set_save)) }
        },
        dismissButton = {
            Row {
                if (currentKey.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            haptics.click()
                            onConfirm(null)
                        },
                    ) { Text(stringResource(R.string.set_lastfm_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
            }
        },
    )
}

@StringRes
private fun AudioQuality.labelRes(): Int = when (this) {
    AudioQuality.BEST -> R.string.set_quality_best
    AudioQuality.DATA_SAVER -> R.string.set_quality_data_saver
}

@StringRes
private fun AudioQuality.descriptionRes(): Int = when (this) {
    AudioQuality.BEST -> R.string.set_quality_best_desc
    AudioQuality.DATA_SAVER -> R.string.set_quality_data_saver_desc
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.set_theme_system
    ThemeMode.LIGHT -> R.string.set_theme_light
    ThemeMode.DARK -> R.string.set_theme_dark
}

// region Previews

@Composable
private fun CategoryPreview(category: SettingsCategory, settings: AppSettings = AppSettings()) {
    SpautifailleTheme(dynamicColor = false) {
        SettingsCategoryScreen(
            category = category,
            state = SettingsUiState(isLoaded = true, settings = settings),
            actions = SettingsActions(),
            versionName = "1.0.0",
            dynamicColorAvailable = true,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun PlaybackPreview() = CategoryPreview(SettingsCategory.PLAYBACK)

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun DownloadsPreview() = CategoryPreview(SettingsCategory.DOWNLOADS)

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun AppearancePreview() = CategoryPreview(SettingsCategory.APPEARANCE, AppSettings(themeMode = ThemeMode.DARK))

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun StoragePreview() = CategoryPreview(SettingsCategory.STORAGE)

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun DiscoveryPreview() = CategoryPreview(SettingsCategory.DISCOVERY, AppSettings(lastFmApiKey = "abc"))

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun AboutPreview() = CategoryPreview(SettingsCategory.ABOUT)

// endregion
