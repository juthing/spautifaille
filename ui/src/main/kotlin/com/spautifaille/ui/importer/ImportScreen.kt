package com.spautifaille.ui.importer

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.ui.res.stringResource
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(event.message(context))
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
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.import_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "file") { FileCard(isBusy = state.isStarting, onPickFile = actions.onPickFile) }
            item(key = "youtube") {
                YouTubeCard(
                    state = state,
                    onUrlChange = actions.onUrlChange,
                    onPaste = actions.onPasteUrl,
                    onImport = actions.onImportUrl,
                )
            }
            if (state.jobs.isNotEmpty()) {
                item(key = "jobs-title") {
                    Text(
                        text = stringResource(R.string.import_jobs_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
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
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileCard(isBusy: Boolean, onPickFile: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.import_file_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.import_file_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPickFile, enabled = !isBusy) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.import_file_button))
            }
        }
    }
}

@Composable
private fun YouTubeCard(
    state: ImportUiState,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.import_youtube_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.import_youtube_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.import_url_label)) },
                singleLine = true,
                isError = state.urlInvalid,
                supportingText = if (state.urlInvalid) {
                    { Text(stringResource(R.string.import_url_invalid)) }
                } else {
                    null
                },
                trailingIcon = {
                    if (state.url.isEmpty()) {
                        IconButton(onClick = onPaste) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.import_url_paste))
                        }
                    } else {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.import_url_clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onImport() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onImport, enabled = state.canImportUrl) {
                    Text(stringResource(R.string.import_youtube_button))
                }
                if (state.isStarting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(
                        text = stringResource(R.string.import_reading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun JobCard(
    job: ImportJob,
    onOpenPlaylist: (Long) -> Unit,
    onOpenReview: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toReview = job.needsReview + job.notFound
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.playlistName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val (status, isError) = when (job.state) {
                ImportJobState.RUNNING -> stringResource(R.string.import_job_running, job.processed, job.total) to false
                ImportJobState.COMPLETED -> stringResource(R.string.import_job_completed, job.total) to false
                ImportJobState.FAILED -> (job.error ?: stringResource(R.string.import_job_failed_default)) to true
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (job.isRunning) {
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { job.targetPlaylistId?.let(onOpenPlaylist) },
                    label = { Text(stringResource(R.string.import_chip_matched, job.matched)) },
                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                if (job.needsReview > 0) {
                    AssistChip(
                        onClick = { onOpenReview(job.id) },
                        label = { Text(stringResource(R.string.import_chip_review, job.needsReview)) },
                        leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                if (job.notFound > 0) {
                    AssistChip(
                        onClick = { onOpenReview(job.id) },
                        label = { Text(stringResource(R.string.import_chip_not_found, job.notFound)) },
                        leadingIcon = { Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (job.targetPlaylistId != null) {
                    TextButton(onClick = { onOpenPlaylist(job.targetPlaylistId!!) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.import_action_open_playlist))
                    }
                }
                if (toReview > 0) {
                    TextButton(onClick = { onOpenReview(job.id) }) {
                        Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.import_action_review))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDelete(job.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.import_action_delete))
                }
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

@Preview(showBackground = true, heightDp = 900)
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

@Preview(showBackground = true)
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
