package com.spautifaille.ui.youtube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.theme.ArtworkSize
import com.spautifaille.ui.theme.Spacing

/**
 * Avatar rond du compte YouTube pour les `TopAppBar` des écrans principaux : photo de profil (32 dp) quand un
 * compte est connecté, sinon `Icons.Filled.AccountCircle` qui mène à la connexion. Au toucher, menu de compte.
 */
@Composable
fun AccountAvatarAction(
    onSignIn: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: YouTubeAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AccountAvatarButton(
        state = state,
        onSignIn = onSignIn,
        onOpenAccount = onOpenAccount,
        onSyncNow = viewModel::syncNow,
        onSignOut = viewModel::signOut,
        modifier = modifier,
    )
}

@Composable
fun AccountAvatarButton(
    state: YouTubeAccountUiState,
    onSignIn: () -> Unit,
    onOpenAccount: () -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val account = state.account

    Column(modifier) {
        IconButton(
            onClick = {
                haptics.click()
                if (account == null) onSignIn() else menuOpen = true
            },
        ) {
            val problemDescription = stringResource(R.string.yt_avatar_problem_description)
            BadgedBox(
                badge = {
                    if (state.hasProblem) Badge(Modifier.semantics { contentDescription = problemDescription })
                },
            ) {
                if (account == null) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = stringResource(R.string.yt_avatar_sign_in_description),
                    )
                } else {
                    Artwork(
                        url = account.avatarUrl,
                        modifier = Modifier.size(ArtworkSize.AccountAvatar),
                        shape = CircleShape,
                        contentDescription = stringResource(R.string.yt_avatar_account_description, account.name),
                        placeholderIcon = Icons.Filled.AccountCircle,
                    )
                }
            }
        }
        if (account != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                AccountMenuHeader(state)
                HorizontalDivider()
                if (state.needsReauth) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.yt_reauth_action)) },
                        leadingIcon = { Icon(Icons.Filled.SyncProblem, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onSignIn()
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.yt_sync_now)) },
                        leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                        enabled = !state.status.isSyncing,
                        onClick = {
                            menuOpen = false
                            onSyncNow()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.yt_menu_account_settings)) },
                    leadingIcon = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpenAccount()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.yt_menu_sign_out)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        confirmSignOut = true
                    },
                )
            }
        }
    }

    if (confirmSignOut) {
        SignOutDialog(
            onConfirm = {
                confirmSignOut = false
                onSignOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** En-tête du menu : photo, nom, e-mail ou identifiant, et l'état de la synchronisation. */
@Composable
private fun AccountMenuHeader(state: YouTubeAccountUiState) {
    val account = state.account ?: return
    Column(
        modifier = Modifier
            .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                url = account.avatarUrl,
                modifier = Modifier.size(ArtworkSize.AccountAvatarLarge),
                shape = CircleShape,
                placeholderIcon = Icons.Filled.AccountCircle,
            )
            Column {
                Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        SyncStatusLine(state)
    }
}

/** « Synchronisé il y a X » / « N actions en attente » / « Synchronisation en cours… » / « Reconnexion nécessaire ». */
@Composable
internal fun SyncStatusLine(state: YouTubeAccountUiState, modifier: Modifier = Modifier) {
    val status = state.status
    val now = remember(status.lastSyncAt, status.pendingActions, status.isSyncing) { System.currentTimeMillis() }
    val (text, isProblem) = when {
        state.needsReauth -> stringResource(R.string.yt_reauth_title) to true
        status.isSyncing -> stringResource(R.string.yt_syncing) to false
        status.lastError != null -> stringResource(R.string.yt_sync_failed_short) to true
        status.pendingActions > 0 ->
            pluralStringResource(R.plurals.yt_pending_actions, status.pendingActions, status.pendingActions) to false
        status.lastSyncAt != null -> syncAge(status.lastSyncAt!!, now).label() to false
        else -> stringResource(R.string.yt_never_synced) to false
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.yt_sign_out_title)) },
        text = { Text(stringResource(R.string.yt_sign_out_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.yt_menu_sign_out)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}

private val MenuMinWidth = 220.dp
private val MenuMaxWidth = 280.dp
