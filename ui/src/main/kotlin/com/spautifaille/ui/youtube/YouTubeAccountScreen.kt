package com.spautifaille.ui.youtube

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.SyncError
import com.spautifaille.domain.youtube.SyncStatus
import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.AppButtonDefaults
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.IconTone
import com.spautifaille.ui.components.LoadingState
import com.spautifaille.ui.components.ToneIconCircle
import com.spautifaille.ui.settings.SettingsGroupLabel
import com.spautifaille.ui.settings.SettingsInfoRow
import com.spautifaille.ui.settings.SettingsNavRow
import com.spautifaille.ui.settings.SettingsNote
import com.spautifaille.ui.settings.SettingsSwitchRow
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ListBottomPadding
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme

/** Actions de la page « Compte YouTube ». */
data class YouTubeAccountActions(
    val onBack: () -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onLikesMusicOnlyChange: (Boolean) -> Unit = {},
    val onSignOut: () -> Unit = {},
)

@Composable
fun YouTubeAccountScreenRoot(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: YouTubeAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Le mode diagnostic n'est qu'un affichage : il se réinitialise avec l'écran.
    var diagnostic by rememberSaveable { mutableStateOf(false) }
    val actions = remember(viewModel, onBack, onSignIn) {
        YouTubeAccountActions(
            onBack = onBack,
            onSignIn = onSignIn,
            onSyncNow = viewModel::syncNow,
            onLikesMusicOnlyChange = viewModel::setLikesMusicOnly,
            onSignOut = viewModel::signOut,
        )
    }
    YouTubeAccountScreen(
        state = state,
        diagnosticMode = diagnostic,
        onDiagnosticModeChange = { diagnostic = it },
        actions = actions,
        modifier = modifier,
    )
}

/**
 * Réglages du compte YouTube : connexion, état de la synchronisation, option des likes, mode diagnostic et
 * déconnexion. Sans compte, l'écran présente la fonction et mène à la connexion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeAccountScreen(
    state: YouTubeAccountUiState,
    diagnosticMode: Boolean,
    onDiagnosticModeChange: (Boolean) -> Unit,
    actions: YouTubeAccountActions,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.yt_account_title)) },
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
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            when {
                !state.isLoaded -> LoadingState()
                state.account == null -> SignedOutContent(onSignIn = actions.onSignIn)
                else -> SignedInContent(
                    state = state,
                    diagnosticMode = diagnosticMode,
                    onDiagnosticModeChange = onDiagnosticModeChange,
                    actions = actions,
                    onSignOutClick = { confirmSignOut = true },
                )
            }
        }
    }
    if (confirmSignOut) {
        SignOutDialog(
            onConfirm = {
                confirmSignOut = false
                actions.onSignOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun SignedOutContent(onSignIn: () -> Unit) {
    val haptics = LocalAppHaptics.current
    Column(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenHorizontalPadding)
            .padding(top = Spacing.l, bottom = ListBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        ToneIconCircle(icon = Icons.Filled.AccountCircle, tone = IconTone.Primary, size = ArtworkSize.AccountAvatarLarge + Spacing.l)
        Text(
            stringResource(R.string.yt_signed_out_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.yt_signed_out_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                haptics.click()
                onSignIn()
            },
            colors = AppButtonDefaults.filledColors(),
        ) { Text(stringResource(R.string.yt_sign_in_button)) }
        Text(
            stringResource(R.string.yt_risk_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignedInContent(
    state: YouTubeAccountUiState,
    diagnosticMode: Boolean,
    onDiagnosticModeChange: (Boolean) -> Unit,
    actions: YouTubeAccountActions,
    onSignOutClick: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val status = state.status
    Column(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = ListBottomPadding),
    ) {
        state.account?.let { AccountHeader(it, Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.m)) }

        if (state.needsReauth) {
            ReauthCard(
                onReauth = actions.onSignIn,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
            )
        }

        SettingsGroupLabel(stringResource(R.string.yt_sync_section))
        SettingsNavRow(
            icon = Icons.Filled.Sync,
            title = stringResource(R.string.yt_sync_now),
            summary = if (status.isSyncing) stringResource(R.string.yt_syncing) else null,
            onClick = { if (!status.isSyncing && !state.needsReauth) actions.onSyncNow() },
            showChevron = false,
        )
        SettingsInfoRow(
            icon = Icons.Filled.Schedule,
            title = stringResource(R.string.yt_last_sync_label),
            summary = status.lastSyncAt?.let { syncAge(it, System.currentTimeMillis()).label() }
                ?: stringResource(R.string.yt_never_synced),
        )
        SettingsInfoRow(
            icon = Icons.Filled.Pending,
            title = stringResource(R.string.yt_pending_label),
            summary = if (status.pendingActions == 0) {
                stringResource(R.string.yt_nothing_pending)
            } else {
                pluralStringResource(R.plurals.yt_pending_actions, status.pendingActions, status.pendingActions)
            },
        )

        SettingsGroupLabel(stringResource(R.string.yt_likes_section))
        SettingsSwitchRow(
            icon = Icons.Filled.Favorite,
            title = stringResource(R.string.yt_likes_music_only),
            summary = stringResource(R.string.yt_likes_music_only_summary),
            checked = state.likesMusicOnly,
            onCheckedChange = actions.onLikesMusicOnlyChange,
        )

        SettingsGroupLabel(stringResource(R.string.yt_diagnostic_section))
        SettingsSwitchRow(
            icon = Icons.Filled.BugReport,
            title = stringResource(R.string.yt_diagnostic_mode),
            summary = stringResource(R.string.yt_diagnostic_mode_summary),
            checked = diagnosticMode,
            onCheckedChange = onDiagnosticModeChange,
            tone = IconTone.Tertiary,
        )
        if (diagnosticMode) {
            DiagnosticCard(
                error = status.lastError,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
            )
        }

        Spacer(Modifier.height(Spacing.l))
        OutlinedButton(
            onClick = {
                haptics.click()
                onSignOutClick()
            },
            modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(Spacing.s))
            Text(stringResource(R.string.yt_menu_sign_out))
        }
        SettingsNote(stringResource(R.string.yt_risk_note), Modifier.padding(top = Spacing.m))
    }
}

@Composable
private fun AccountHeader(account: YouTubeAccount, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = account.avatarUrl,
            modifier = Modifier.size(ArtworkSize.AccountAvatarLarge),
            shape = CircleShape,
            placeholderIcon = Icons.Filled.AccountCircle,
        )
        Column(Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            (account.email ?: account.handle)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReauthCard(onReauth: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    stringResource(R.string.yt_reauth_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                stringResource(R.string.yt_reauth_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            FilledTonalButton(onClick = onReauth, colors = AppButtonDefaults.tonalColors()) {
                Text(stringResource(R.string.yt_reauth_action))
            }
        }
    }
}

/** Dernière erreur de synchronisation : endpoint, message, et quand. */
@Composable
private fun DiagnosticCard(error: SyncError?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (error == null) {
                Text(stringResource(R.string.yt_diagnostic_no_error), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.yt_diagnostic_error_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    DateUtils.getRelativeTimeSpanString(
                        error.atMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.yt_diagnostic_endpoint, error.endpoint), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.yt_diagnostic_message, error.message), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// region Previews

private val PreviewAccount = YouTubeAccount("Camille Martin", "camille@example.com", "@camille", null)

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun YouTubeAccountSignedOutPreview() {
    SpautifailleTheme(dynamicColor = false) {
        YouTubeAccountScreen(
            state = YouTubeAccountUiState(isLoaded = true),
            diagnosticMode = false,
            onDiagnosticModeChange = {},
            actions = YouTubeAccountActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun YouTubeAccountSignedInPreview() {
    SpautifailleTheme(dynamicColor = false) {
        YouTubeAccountScreen(
            state = YouTubeAccountUiState(
                isLoaded = true,
                accountState = AccountState.SignedIn(PreviewAccount),
                status = SyncStatus(
                    lastSyncAt = System.currentTimeMillis() - 5 * 60_000L,
                    pendingActions = 2,
                    lastError = SyncError("browse", "2 entrées illisibles", System.currentTimeMillis() - 60_000L),
                ),
            ),
            diagnosticMode = true,
            onDiagnosticModeChange = {},
            actions = YouTubeAccountActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun YouTubeAccountReauthPreview() {
    SpautifailleTheme(dynamicColor = false) {
        YouTubeAccountScreen(
            state = YouTubeAccountUiState(isLoaded = true, accountState = AccountState.ReauthRequired(PreviewAccount)),
            diagnosticMode = false,
            onDiagnosticModeChange = {},
            actions = YouTubeAccountActions(),
        )
    }
}

// endregion
