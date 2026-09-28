package com.spautifaille.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.ui.R
import com.spautifaille.ui.library.LibraryContentMaxWidth

@Immutable
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onAudioQualityChange: (AudioQuality) -> Unit = {},
    val onWifiOnlyChange: (Boolean) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    val onCacheSizeChange: (Int) -> Unit = {},
    val onLastFmKeyChange: (String?) -> Unit = {},
)

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
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
            onDynamicColorChange = viewModel::setDynamicColor,
            onCacheSizeChange = viewModel::setStreamCacheSizeMb,
            onLastFmKeyChange = viewModel::setLastFmApiKey,
        )
    }
    SettingsScreen(
        state = state,
        actions = actions,
        versionName = versionName,
        dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        modifier = modifier,
    )
}

private fun Context.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

private enum class SettingsDialog { Quality, Theme, CacheSize, LastFm }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    versionName: String,
    dynamicColorAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lib_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lib_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = LibraryContentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsSection(R.string.lib_settings_playback) {
                    SettingsItem(
                        icon = Icons.Filled.GraphicEq,
                        title = stringResource(R.string.lib_settings_audio_quality),
                        summary = stringResource(settings.audioQuality.labelRes()),
                        onClick = { dialog = SettingsDialog.Quality },
                    )
                }
                SettingsSection(R.string.lib_settings_downloads) {
                    SettingsItem(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.lib_settings_wifi_only),
                        summary = stringResource(R.string.lib_settings_wifi_only_summary),
                        onClick = { actions.onWifiOnlyChange(!settings.downloadOverWifiOnly) },
                        trailing = {
                            Switch(
                                checked = settings.downloadOverWifiOnly,
                                onCheckedChange = null,
                            )
                        },
                    )
                    SettingsItem(
                        icon = Icons.Filled.Download,
                        title = stringResource(R.string.lib_settings_manage_downloads),
                        onClick = actions.onOpenDownloads,
                        trailing = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                    )
                }
                SettingsSection(R.string.lib_settings_appearance) {
                    SettingsItem(
                        icon = Icons.Filled.Brightness6,
                        title = stringResource(R.string.lib_settings_theme),
                        summary = stringResource(settings.themeMode.labelRes()),
                        onClick = { dialog = SettingsDialog.Theme },
                    )
                    if (dynamicColorAvailable) {
                        SettingsItem(
                            icon = Icons.Filled.Palette,
                            title = stringResource(R.string.lib_settings_dynamic_color),
                            summary = stringResource(R.string.lib_settings_dynamic_color_summary),
                            onClick = { actions.onDynamicColorChange(!settings.dynamicColor) },
                            trailing = { Switch(checked = settings.dynamicColor, onCheckedChange = null) },
                        )
                    }
                }
                SettingsSection(R.string.lib_settings_storage) {
                    SettingsItem(
                        icon = Icons.Filled.Sd,
                        title = stringResource(R.string.lib_settings_cache_size),
                        summary = formatCacheSize(settings.streamCacheSizeMb),
                        onClick = { dialog = SettingsDialog.CacheSize },
                    )
                }
                SettingsSection(R.string.lib_settings_discovery) {
                    SettingsItem(
                        icon = Icons.Filled.Key,
                        title = stringResource(R.string.lib_settings_lastfm_key),
                        summary = stringResource(
                            if (state.hasLastFmKey) R.string.lib_settings_lastfm_configured
                            else R.string.lib_settings_lastfm_not_configured,
                        ),
                        onClick = { dialog = SettingsDialog.LastFm },
                    )
                }
                SettingsSection(R.string.lib_settings_about, showDivider = false) {
                    SettingsItem(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.lib_settings_version),
                        summary = versionName.ifBlank { stringResource(R.string.lib_settings_version_unknown) },
                    )
                    SettingsItem(
                        icon = Icons.Filled.Gavel,
                        title = stringResource(R.string.lib_settings_license),
                        summary = stringResource(R.string.lib_settings_license_value),
                    )
                    SettingsItem(
                        icon = Icons.Filled.MusicNote,
                        title = stringResource(R.string.lib_settings_powered_by),
                        summary = stringResource(R.string.lib_settings_powered_by_value),
                    )
                }
            }
        }
    }

    when (dialog) {
        SettingsDialog.Quality -> RadioDialog(
            title = stringResource(R.string.lib_settings_audio_quality),
            options = AudioQuality.entries,
            selected = settings.audioQuality,
            label = { stringResource(it.labelRes()) },
            description = { stringResource(it.descriptionRes()) },
            onSelect = {
                actions.onAudioQualityChange(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Theme -> RadioDialog(
            title = stringResource(R.string.lib_settings_theme),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                actions.onThemeModeChange(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.CacheSize -> RadioDialog(
            title = stringResource(R.string.lib_settings_cache_size),
            options = SettingsViewModel.CacheSizeOptionsMb,
            selected = settings.streamCacheSizeMb,
            label = { formatCacheSize(it) },
            onSelect = {
                actions.onCacheSizeChange(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.LastFm -> LastFmKeyDialog(
            currentKey = settings.lastFmApiKey.orEmpty(),
            onConfirm = {
                actions.onLastFmKeyChange(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SettingsSection(
    @StringRes title: Int,
    showDivider: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        )
        content()
        if (showDivider) HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing,
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
    )
}

/** Dialogue à choix unique (boutons radio). */
@Composable
private fun <T> RadioDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: (@Composable (T) -> String)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                            if (description != null) {
                                Text(
                                    description(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

@Composable
private fun LastFmKeyDialog(currentKey: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_settings_lastfm_key)) },
        text = {
            Column {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.lib_settings_lastfm_key_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    supportingText = { Text(stringResource(R.string.lib_settings_lastfm_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(key.trim().ifEmpty { null }) }) {
                Text(stringResource(R.string.lib_save))
            }
        },
        dismissButton = {
            Row {
                if (currentKey.isNotEmpty()) {
                    TextButton(onClick = { onConfirm(null) }) { Text(stringResource(R.string.lib_settings_lastfm_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) }
            }
        },
    )
}

@StringRes
private fun AudioQuality.labelRes(): Int = when (this) {
    AudioQuality.BEST -> R.string.lib_quality_best
    AudioQuality.DATA_SAVER -> R.string.lib_quality_data_saver
}

@StringRes
private fun AudioQuality.descriptionRes(): Int = when (this) {
    AudioQuality.BEST -> R.string.lib_quality_best_desc
    AudioQuality.DATA_SAVER -> R.string.lib_quality_data_saver_desc
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.lib_theme_system
    ThemeMode.LIGHT -> R.string.lib_theme_light
    ThemeMode.DARK -> R.string.lib_theme_dark
}

/** 256 → « 256 Mo », 1024 → « 1 Go », 1536 → « 1,5 Go ». */
@Composable
internal fun formatCacheSize(sizeMb: Int): String =
    if (sizeMb >= 1024) {
        val gb = sizeMb / 1024.0
        if (sizeMb % 1024 == 0) stringResource(R.string.lib_size_gb, sizeMb / 1024)
        else stringResource(R.string.lib_size_gb_decimal, gb)
    } else {
        stringResource(R.string.lib_size_mb, sizeMb)
    }

// region Previews

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun SettingsPreview() {
    MaterialTheme {
        SettingsScreen(
            state = SettingsUiState(isLoaded = true, settings = AppSettings()),
            actions = SettingsActions(),
            versionName = "1.0.0",
            dynamicColorAvailable = true,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun SettingsNoDynamicColorPreview() {
    MaterialTheme {
        SettingsScreen(
            state = SettingsUiState(isLoaded = true, settings = AppSettings(lastFmApiKey = "abc")),
            actions = SettingsActions(),
            versionName = "1.0.0",
            dynamicColorAvailable = false,
        )
    }
}

// endregion
