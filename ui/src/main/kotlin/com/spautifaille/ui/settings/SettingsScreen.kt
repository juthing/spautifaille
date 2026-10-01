package com.spautifaille.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.ui.R
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

/**
 * Page d'accueil des réglages (onglet racine) : une ligne par catégorie. [onOpenCategory] reçoit les
 * catégories à sous-page, [onOpenImport] la catégorie « Importer » qui ouvre directement l'écran d'import.
 */
@Composable
fun SettingsRoute(
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = remember(context) { context.appVersionName() }
    SettingsScreen(
        state = state,
        versionName = versionName,
        dynamicColorAvailable = dynamicColorSupported(),
        onCategoryClick = { category ->
            if (category.opensSubpage) onOpenCategory(category) else onOpenImport()
        },
        modifier = modifier,
    )
}

internal fun dynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

internal fun Context.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    versionName: String,
    dynamicColorAvailable: Boolean,
    onCategoryClick: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.set_title)) }, scrollBehavior = scrollBehavior) },
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
                    .padding(horizontal = ScreenHorizontalPadding)
                    .padding(top = Spacing.s, bottom = ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                SettingsCategory.entries.forEach { category ->
                    SettingsCategoryRow(
                        category = category,
                        summary = settingsSummary(category, state.settings, dynamicColorAvailable, versionName)
                            .resolveSummary(),
                        onClick = { onCategoryClick(category) },
                    )
                }
            }
        }
    }
}

// region Previews

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SettingsPreview() {
    SpautifailleTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(isLoaded = true, settings = AppSettings()),
            versionName = "1.0.0",
            dynamicColorAvailable = true,
            onCategoryClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() {
    SpautifailleTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(isLoaded = true, settings = AppSettings(lastFmApiKey = "abc")),
            versionName = "1.0.0",
            dynamicColorAvailable = false,
            onCategoryClick = {},
        )
    }
}

// endregion
