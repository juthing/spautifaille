package com.spautifaille.ui.importer

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.IconTone
import com.spautifaille.ui.components.ToneIconCircle
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
import com.spautifaille.ui.theme.SpautifailleTheme
import kotlinx.coroutines.launch

/** Types MIME proposés au sélecteur de documents (les exports CSV/JSON sont souvent typés `octet-stream` ou `text/plain`). */
private val ImportMimeTypes = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/json",
    "text/plain",
    "text/*",
    "application/octet-stream",
)

@Immutable
data class ImportActions(
    val onBack: () -> Unit = {},
    val onPickFile: () -> Unit = {},
    val onUrlChange: (String) -> Unit = {},
    val onPasteUrl: () -> Unit = {},
    val onImportUrl: () -> Unit = {},
    val onOpenPlaylist: (Long) -> Unit = {},
    val onOpenReview: (Long) -> Unit = {},
    val onDeleteJob: (Long) -> Unit = {},
)

@Composable
fun ImportScreenRoot(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenReview: (jobId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = LocalAppHaptics.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is ImportEvent.Failed) haptics.reject()
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(event.message(context))
        }
    }

    // Retour haptique quand un import en cours se termine (réussi ou interrompu).
    val previousStates = remember { mutableMapOf<Long, ImportJobState>() }
    LaunchedEffect(state.jobs) {
        state.jobs.forEach { job ->
            when (jobFeedback(previousStates[job.id], job.state)) {
                JobFeedback.CONFIRM -> haptics.confirm()
                JobFeedback.REJECT -> haptics.reject()
                null -> Unit
            }
            previousStates[job.id] = job.state
        }
    }

    val actions = remember(viewModel, picker, clipboard, onBack, onOpenPlaylist, onOpenReview) {
        ImportActions(
            onBack = onBack,
            onPickFile = { picker.launch(ImportMimeTypes) },
            onUrlChange = viewModel::onUrlChanged,
            onPasteUrl = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank()) viewModel.onUrlChanged(text.trim())
                }
            },
            onImportUrl = viewModel::importUrl,
            onOpenPlaylist = onOpenPlaylist,
            onOpenReview = onOpenReview,
            onDeleteJob = viewModel::deleteJob,
        )
    }
    ImportScreen(state = state, actions = actions, snackbarHostState = snackbarHostState, modifier = modifier)
}

private fun ImportEvent.message(context: Context): String = when (this) {
    is ImportEvent.Started -> context.resources.getQuantityString(R.plurals.import_started, count, count)
    is ImportEvent.Failed -> message.asString(context)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    state: ImportUiState,
    actions: ImportActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ScreenHorizontalPadding,
                    end = ScreenHorizontalPadding,
                    top = padding.calculateTopPadding() + Spacing.s,
                    bottom = padding.calculateBottomPadding() + Spacing.l,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                item(key = "intro") {
                    Text(
                        text = stringResource(R.string.import_intro),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item(key = "starting") {
                    AnimatedVisibility(visible = state.isStarting) { StartingBanner() }
                }
                item(key = "file") { FileCard(isBusy = state.isStarting, onPickFile = actions.onPickFile) }
                item(key = "link") {
                    LinkCard(
                        state = state,
                        onUrlChange = actions.onUrlChange,
                        onPaste = actions.onPasteUrl,
                        onImport = actions.onImportUrl,
                    )
                }
                if (state.jobs.isNotEmpty()) {
                    item(key = "jobs-title") {
                        Text(
                            text = stringResource(R.string.import_recent_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    items(state.jobs, key = { it.id }) { job ->
                        JobCard(
                            job = job,
                            onOpenPlaylist = actions.onOpenPlaylist,
                            onOpenReview = actions.onOpenReview,
                            onDelete = actions.onDeleteJob,
                        )
                    }
                } else if (!state.isLoadingJobs) {
                    item(key = "jobs-empty") {
                        Text(
                            text = stringResource(R.string.import_jobs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartingBanner(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(R.string.import_reading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** Carte de choix d'une source : icône tonale, titre, description, puis contenu propre à la source. */
@Composable
private fun SourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: IconTone,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                ToneIconCircle(icon = icon, tone = tone, size = 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileCard(isBusy: Boolean, onPickFile: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalAppHaptics.current
    SourceCard(
        icon = Icons.Filled.UploadFile,
        tone = IconTone.Primary,
        title = stringResource(R.string.import_file_title),
        description = stringResource(R.string.import_file_card_description),
        modifier = modifier,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            FormatBadge(stringResource(R.string.import_format_exportify))
            FormatBadge(stringResource(R.string.import_format_spotify))
            FormatBadge(stringResource(R.string.import_format_takeout))
            FormatBadge(stringResource(R.string.import_format_generic))
        }
        Button(
            onClick = {
                haptics.click()
                onPickFile()
            },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.s))
            Text(stringResource(R.string.import_file_button))
        }
    }
}

@Composable
private fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.s + Spacing.xs, vertical = Spacing.xs + Spacing.xxs),
        )
    }
}

@Composable
private fun LinkCard(
    state: ImportUiState,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    SourceCard(
        icon = Icons.Filled.Link,
        tone = IconTone.Tertiary,
        title = stringResource(R.string.import_link_title),
        description = stringResource(R.string.import_link_description),
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            label = { Text(stringResource(R.string.import_url_label)) },
            placeholder = { Text(stringResource(R.string.import_link_hint)) },
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
            singleLine = true,
            isError = state.urlInvalid,
            supportingText = if (state.urlInvalid) {
                { Text(stringResource(R.string.import_url_invalid)) }
            } else {
                null
            },
            trailingIcon = if (state.url.isNotEmpty()) {
                {
                    IconButton(onClick = {
                        haptics.click()
                        onUrlChange("")
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.import_url_clear))
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (state.canImportUrl) onImport() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    haptics.click()
                    onPaste()
                },
                enabled = !state.isStarting,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.import_url_paste))
            }
            Button(
                onClick = {
                    haptics.click()
                    onImport()
                },
                enabled = state.canImportUrl,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Spacer(Modifier.size(Spacing.s))
                }
                Text(stringResource(R.string.import_youtube_button))
            }
        }
    }
}

@Composable
private fun ImportStage.label(): String = stringResource(
    when (this) {
        ImportStage.MATCHING -> R.string.import_stage_matching
        ImportStage.NEEDS_REVIEW -> R.string.import_stage_review
        ImportStage.DONE -> R.string.import_stage_done
        ImportStage.FAILED -> R.string.import_stage_failed
    },
)

private fun ImportStage.tone(): IconTone = when (this) {
    ImportStage.MATCHING -> IconTone.Primary
    ImportStage.NEEDS_REVIEW -> IconTone.Tertiary
    ImportStage.DONE -> IconTone.Secondary
    ImportStage.FAILED -> IconTone.Error
}

private fun ImportStage.icon() = when (this) {
    ImportStage.MATCHING -> Icons.Filled.Sync
    ImportStage.NEEDS_REVIEW -> Icons.Filled.Warning
    ImportStage.DONE -> Icons.Filled.CheckCircle
    ImportStage.FAILED -> Icons.Filled.ErrorOutline
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobCard(
    job: ImportJob,
    onOpenPlaylist: (Long) -> Unit,
    onOpenReview: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val stage = job.stage()
    val toReview = job.needsReview + job.notFound
    val playlistId = job.targetPlaylistId
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s + Spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                ToneIconCircle(icon = stage.icon(), tone = stage.tone(), size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(job.playlistName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = stage.label(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (stage == ImportStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    haptics.click()
                    onDelete(job.id)
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.import_delete_job_description, job.playlistName),
                    )
                }
            }

            StepsRow(stage)

            if (job.isRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = stringResource(R.string.import_progress_counter, job.processed, job.total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.import_progress_percent, (job.progress * 100).toInt()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (stage == ImportStage.FAILED) {
                Text(
                    text = job.error ?: stringResource(R.string.import_job_failed_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = pluralStringResource(R.plurals.import_tracks_total, job.total, job.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                AssistChip(
                    onClick = {
                        haptics.click()
                        playlistId?.let(onOpenPlaylist)
                    },
                    label = { Text(stringResource(R.string.import_chip_matched, job.matched)) },
                    leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                if (job.needsReview > 0) {
                    AssistChip(
                        onClick = {
                            haptics.click()
                            onOpenReview(job.id)
                        },
                        label = { Text(stringResource(R.string.import_chip_review, job.needsReview)) },
                        leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                if (job.notFound > 0) {
                    AssistChip(
                        onClick = {
                            haptics.click()
                            onOpenReview(job.id)
                        },
                        label = { Text(stringResource(R.string.import_chip_not_found, job.notFound)) },
                        leadingIcon = { Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }

            if (playlistId != null || toReview > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    if (toReview > 0) {
                        FilledTonalButton(onClick = {
                            haptics.click()
                            onOpenReview(job.id)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(Spacing.s))
                            Text(stringResource(R.string.import_action_review))
                        }
                    }
                    if (playlistId != null) {
                        TextButton(onClick = {
                            haptics.click()
                            onOpenPlaylist(playlistId)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(Spacing.s))
                            Text(stringResource(R.string.import_action_open_playlist))
                        }
                    }
                }
            }
        }
    }
}

/** Trois étapes (lecture, correspondance, vérification) avec leur état. */
@Composable
private fun StepsRow(stage: ImportStage, modifier: Modifier = Modifier) {
    val labels = listOf(R.string.import_step_read, R.string.import_step_match, R.string.import_step_review)
    val states = stepStates(stage)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        labels.forEachIndexed { index, label ->
            val step = states[index]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                when (step) {
                    StepState.DONE -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    StepState.CURRENT -> if (stage == ImportStage.MATCHING) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        // Job terminé avec des titres à vérifier : étape courante figée, sans rotation.
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    StepState.FAILED -> Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    StepState.PENDING -> Icon(
                        Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (step == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// region Previews

private fun previewJob(
    id: Long,
    name: String,
    state: ImportJobState,
    total: Int = 120,
    processed: Int = total,
    matched: Int = 100,
    review: Int = 12,
    notFound: Int = 8,
    error: String? = null,
) = ImportJob(id, name, ImportFormat.EXPORTIFY_CSV, state, total, processed, matched, review, notFound, id, error, 0L)

private val PreviewJobs = listOf(
    previewJob(1, "Road trip", ImportJobState.RUNNING, processed = 45, matched = 40, review = 3, notFound = 2),
    previewJob(2, "Favoris Spotify", ImportJobState.COMPLETED),
    previewJob(3, "Playlist YouTube", ImportJobState.COMPLETED, matched = 120, review = 0, notFound = 0),
    previewJob(4, "Export cassé", ImportJobState.FAILED, processed = 10, matched = 10, review = 0, notFound = 0, error = "L'import a été interrompu."),
)

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun ImportScreenPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ImportScreen(
            state = ImportUiState(url = "https://music.youtube.com/playlist?list=PL", canImportUrl = true, isLoadingJobs = false, jobs = PreviewJobs),
            actions = ImportActions(),
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ImportScreenEmptyInvalidPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ImportScreen(
            state = ImportUiState(url = "https://exemple.fr", urlInvalid = true, isStarting = true, isLoadingJobs = false),
            actions = ImportActions(),
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

// endregion
