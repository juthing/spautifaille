package com.spautifaille.ui.importer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.Dp
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.theme.ContentMaxWidth
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportItem
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.MatchCandidate
import com.spautifaille.domain.importer.MatchResult
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.Track
import com.spautifaille.ui.R
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.Artwork
import com.spautifaille.ui.components.EmptyState
import com.spautifaille.ui.components.TrackListPlaceholder
import com.spautifaille.ui.components.bringIntoViewWhenFocusedWithIme
import com.spautifaille.ui.components.formatDuration
import com.spautifaille.ui.components.imeAwareContentWindowInsets
import com.spautifaille.ui.theme.SpautifailleTheme

@Immutable
data class ImportReviewActions(
    val onBack: () -> Unit = {},
    val onFilterChange: (ReviewFilter) -> Unit = {},
    val onChoose: (itemId: Long, track: Track) -> Unit = { _, _ -> },
    val onExclude: (itemId: Long) -> Unit = {},
    val onToggleAlternatives: (itemId: Long) -> Unit = {},
    val onStartSearch: (itemId: Long) -> Unit = {},
    val onSearchQueryChange: (String) -> Unit = {},
    val onSubmitSearch: () -> Unit = {},
    val onCloseSearch: () -> Unit = {},
)

/** L'argument de navigation `jobId` est lu par le ViewModel dans son `SavedStateHandle`. */
@Composable
fun ImportReviewScreenRoot(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportReviewEvent.Failed -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(event.message.asString(context))
                }
            }
        }
    }

    val actions = remember(viewModel, onBack) {
        ImportReviewActions(
            onBack = onBack,
            onFilterChange = viewModel::setFilter,
            onChoose = viewModel::choose,
            onExclude = viewModel::exclude,
            onToggleAlternatives = viewModel::toggleAlternatives,
            onStartSearch = viewModel::startSearch,
            onSearchQueryChange = viewModel::onSearchQueryChanged,
            onSubmitSearch = viewModel::submitSearch,
            onCloseSearch = viewModel::closeSearch,
        )
    }
    ImportReviewScreen(state = state, actions = actions, snackbarHostState = snackbarHostState, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportReviewScreen(
    state: ImportReviewUiState,
    actions: ImportReviewActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    // Haptique centralisée ici : « confirm » quand un titre est validé / choisi, « tick » pour un filtre, « click » sinon.
    val hapticActions = remember(actions, haptics) {
        actions.copy(
            onFilterChange = {
                haptics.tick()
                actions.onFilterChange(it)
            },
            onChoose = { itemId, track ->
                haptics.confirm()
                actions.onChoose(itemId, track)
            },
            onExclude = {
                haptics.click()
                actions.onExclude(it)
            },
            onToggleAlternatives = {
                haptics.click()
                actions.onToggleAlternatives(it)
            },
            onStartSearch = {
                haptics.click()
                actions.onStartSearch(it)
            },
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.import_review_title))
                        state.job?.let {
                            Text(
                                text = it.playlistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Le champ de recherche manuelle est dans la liste : le clavier fait partie des insets du contenu.
        contentWindowInsets = imeAwareContentWindowInsets(),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentAlignment = Alignment.TopCenter,
        ) {
            val job = state.job
            when {
                state.isLoading -> TrackListPlaceholder()
                state.isNotFound || job == null -> EmptyState(title = stringResource(R.string.import_review_not_found))
                else -> ReviewList(
                    state = state,
                    job = job,
                    actions = hapticActions,
                    bottomPadding = padding.calculateBottomPadding(),
                )
            }
        }
    }
}

@Composable
private fun ReviewList(
    state: ImportReviewUiState,
    job: ImportJob,
    actions: ImportReviewActions,
    bottomPadding: Dp,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s + Spacing.xs),
    ) {
        item(key = "summary") {
            ReviewSummaryCard(
                state = state,
                job = job,
                onFilterChange = actions.onFilterChange,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
            )
        }
        stickyHeader(key = "filters") {
            FilterRow(
                state = state,
                onFilterChange = actions.onFilterChange,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            )
        }
        if (state.items.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(
                        when (state.filter) {
                            ReviewFilter.NEEDS_REVIEW -> R.string.import_review_empty_review
                            ReviewFilter.NOT_FOUND -> R.string.import_review_empty_not_found
                            ReviewFilter.ALL -> R.string.import_review_empty_all
                        },
                    ),
                    icon = Icons.Filled.Check,
                    modifier = Modifier.height(280.dp),
                )
            }
        } else {
            items(state.items, key = { it.id }) { item ->
                ReviewItemCard(
                    item = item,
                    expanded = state.expandedItemId == item.id,
                    search = state.search?.takeIf { it.itemId == item.id },
                    actions = actions,
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
                )
            }
        }
    }
}

/** Résumé en haut de la revue : total, progression éventuelle et trois compteurs (trouvés / à vérifier / introuvables). */
@Composable
private fun ReviewSummaryCard(
    state: ImportReviewUiState,
    job: ImportJob,
    onFilterChange: (ReviewFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s + Spacing.xs)) {
            Text(
                text = pluralStringResource(R.plurals.import_tracks_total, job.total, job.total),
                style = MaterialTheme.typography.titleMedium,
            )
            if (job.isRunning) {
                Text(
                    text = stringResource(R.string.import_review_running, job.processed, job.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    count = job.matched,
                    label = stringResource(R.string.import_review_stat_matched),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    count = state.needsReviewCount,
                    label = stringResource(R.string.import_review_stat_review),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = { onFilterChange(ReviewFilter.NEEDS_REVIEW) },
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    count = state.notFoundCount,
                    label = stringResource(R.string.import_review_stat_not_found),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { onFilterChange(ReviewFilter.NOT_FOUND) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.needsReviewCount > 0) {
                Text(
                    text = stringResource(R.string.import_review_validate_all_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    count: Int,
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tileContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.s + Spacing.xs, horizontal = Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = content)
            Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium, color = container, content = tileContent)
    } else {
        Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = container, content = tileContent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(state: ImportReviewUiState, onFilterChange: (ReviewFilter) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        FilterChipFor(ReviewFilter.NEEDS_REVIEW, R.string.import_review_filter_review, state.needsReviewCount, state, onFilterChange)
        FilterChipFor(ReviewFilter.NOT_FOUND, R.string.import_review_filter_not_found, state.notFoundCount, state, onFilterChange)
        FilterChipFor(ReviewFilter.ALL, R.string.import_review_filter_all, state.totalCount, state, onFilterChange)
    }
}

@Composable
private fun FilterChipFor(
    filter: ReviewFilter,
    label: Int,
    count: Int,
    state: ImportReviewUiState,
    onFilterChange: (ReviewFilter) -> Unit,
) {
    FilterChip(
        selected = state.filter == filter,
        onClick = { onFilterChange(filter) },
        label = { Text(stringResource(R.string.import_review_filter_count, stringResource(label), count)) },
    )
}

@Composable
private fun ReviewItemCard(
    item: ImportItem,
    expanded: Boolean,
    search: ReviewSearchState?,
    actions: ImportReviewActions,
    modifier: Modifier = Modifier,
) {
    val result = item.result
    val best = result.best
    val choices = listOfNotNull(best) + result.alternatives
    val canResolve = result.status != MatchStatus.PENDING
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            // Titre tel que lu dans la source.
            Row(
                modifier = Modifier.padding(horizontal = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.import_review_source_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(item.source.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.source.subtitle().takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                StatusBadge(result.status)
            }
            HorizontalDivider(Modifier.padding(vertical = Spacing.xs))

            // Candidat retenu.
            if (best != null) {
                CandidateRow(candidate = best, status = null)
            } else {
                Text(
                    text = stringResource(
                        if (result.status == MatchStatus.PENDING) R.string.import_review_pending else {
                            if (choices.isEmpty()) R.string.import_review_no_candidate else R.string.import_review_excluded
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
            }

            if (canResolve) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (best != null && result.status == MatchStatus.NEEDS_REVIEW) {
                        FilledTonalButton(onClick = { actions.onChoose(item.id, best.track) }) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.import_review_validate))
                        }
                    }
                    if (choices.size > 1 || (best == null && choices.isNotEmpty())) {
                        TextButton(onClick = { actions.onToggleAlternatives(item.id) }) {
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(if (expanded) R.string.import_review_alternatives_hide else R.string.import_review_alternatives))
                        }
                    }
                    TextButton(onClick = { actions.onStartSearch(item.id) }) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.import_review_search))
                    }
                    if (best != null) {
                        TextButton(onClick = { actions.onExclude(item.id) }) {
                            Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.import_review_exclude))
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = Spacing.xs)) {
                    HorizontalDivider()
                    choices.forEach { candidate ->
                        val selected = candidate.track.id == best?.track?.id
                        CandidateRow(
                            candidate = candidate,
                            status = null,
                            modifier = Modifier.selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { if (!selected) actions.onChoose(item.id, candidate.track) },
                            ),
                            leading = {
                                RadioButton(selected = selected, onClick = null)
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(visible = search != null) {
                if (search != null) SearchPanel(search = search, actions = actions, itemId = item.id)
            }
        }
    }
}

/** Ligne de candidat : pochette, titre, artiste · durée et score. [leading] remplace la pochette-seule (bouton radio). */
@Composable
private fun CandidateRow(
    candidate: MatchCandidate,
    status: MatchStatus?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val track = candidate.track
    val subtitle = track.durationMs?.let { stringResource(R.string.common_track_subtitle, track.artist, formatDuration(it)) }
        ?: track.artist
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                leading?.invoke()
                Artwork(url = track.thumbnailUrl, modifier = Modifier.size(48.dp))
            }
        },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.import_review_score, scorePercent(candidate.score)),
                    style = MaterialTheme.typography.labelLarge,
                    color = scoreColor(candidate.score),
                )
                if (status != null) {
                    Text(
                        text = stringResource(status.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun scoreColor(score: Double) = when {
    score >= 0.85 -> MaterialTheme.colorScheme.primary
    score >= 0.6 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** Pastille d'état d'un titre importé (couleurs de conteneur du thème). */
@Composable
private fun StatusBadge(status: MatchStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        MatchStatus.MATCHED -> scheme.primaryContainer to scheme.onPrimaryContainer
        MatchStatus.NEEDS_REVIEW -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        MatchStatus.NOT_FOUND -> scheme.errorContainer to scheme.onErrorContainer
        MatchStatus.PENDING -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text = stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
        )
    }
}

private fun MatchStatus.labelRes(): Int = when (this) {
    MatchStatus.MATCHED -> R.string.import_review_status_matched
    MatchStatus.NEEDS_REVIEW -> R.string.import_review_status_review
    MatchStatus.NOT_FOUND -> R.string.import_review_status_not_found
    MatchStatus.PENDING -> R.string.import_review_status_pending
}

@Composable
private fun SearchPanel(search: ReviewSearchState, itemId: Long, actions: ImportReviewActions) {
    Column(Modifier.padding(horizontal = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        HorizontalDivider()
        OutlinedTextField(
            value = search.query,
            onValueChange = actions.onSearchQueryChange,
            modifier = Modifier.fillMaxWidth().bringIntoViewWhenFocusedWithIme(),
            label = { Text(stringResource(R.string.import_review_search_label)) },
            singleLine = true,
            trailingIcon = {
                Row {
                    IconButton(onClick = actions.onSubmitSearch, enabled = search.query.isNotBlank() && !search.isSearching) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.import_review_search_submit))
                    }
                    IconButton(onClick = actions.onCloseSearch) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.import_review_search_close))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onSubmitSearch() }),
        )
        when {
            search.isSearching -> Box(Modifier.fillMaxWidth().padding(Spacing.s), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            search.error != null -> Text(
                text = stringResource(search.error.toMessage()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            search.hasSearched && search.results.isEmpty() -> Text(
                text = stringResource(R.string.import_review_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column {
                search.results.forEach { track ->
                    val subtitle = track.durationMs?.let { stringResource(R.string.common_track_subtitle, track.artist, formatDuration(it)) }
                        ?: track.artist
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button, onClick = { actions.onChoose(itemId, track) }),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Artwork(url = track.thumbnailUrl, modifier = Modifier.size(48.dp)) },
                        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

// region Previews

private fun pt(id: String, title: String, artist: String, durationMs: Long = 215_000L) =
    Track(id = id, title = title, artist = artist, durationMs = durationMs)

private val PreviewItems = listOf(
    ImportItem(
        1, 1, 0,
        ImportedTrack("Blinding Lights", listOf("The Weeknd"), durationMs = 200_000L),
        MatchResult(
            MatchStatus.NEEDS_REVIEW,
            MatchCandidate(pt("a", "Blinding Lights (Live)", "The Weeknd"), 0.72),
            listOf(MatchCandidate(pt("b", "Blinding Lights", "The Weeknd Topic", 201_000L), 0.68), MatchCandidate(pt("c", "Blinding Lights - Karaoke", "Karaoke Hits"), 0.41)),
        ),
    ),
    ImportItem(
        2, 1, 1,
        ImportedTrack("Un titre très rare avec un nom vraiment très long pour tester le débordement", listOf("Artiste inconnu", "Second artiste")),
        MatchResult(MatchStatus.NOT_FOUND, null, emptyList()),
    ),
    ImportItem(
        3, 1, 2,
        ImportedTrack("Levitating", listOf("Dua Lipa"), durationMs = 203_000L),
        MatchResult(MatchStatus.MATCHED, MatchCandidate(pt("d", "Levitating", "Dua Lipa", 203_000L), 0.98), emptyList()),
    ),
)

private val PreviewJob = ImportJob(1, "Road trip", ImportFormat.EXPORTIFY_CSV, ImportJobState.RUNNING, 120, 45, 40, 1, 1, 1, null, 0L)

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ImportReviewPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ImportReviewScreen(
            state = ImportReviewUiState(
                isLoading = false,
                job = PreviewJob,
                filter = ReviewFilter.ALL,
                items = PreviewItems,
                needsReviewCount = 1,
                notFoundCount = 1,
                totalCount = 3,
                expandedItemId = 1,
            ),
            actions = ImportReviewActions(),
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun ImportReviewSearchPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ImportReviewScreen(
            state = ImportReviewUiState(
                isLoading = false,
                job = PreviewJob.copy(state = ImportJobState.COMPLETED, processed = 120),
                filter = ReviewFilter.NOT_FOUND,
                items = PreviewItems.filter { it.result.status == MatchStatus.NOT_FOUND },
                needsReviewCount = 1,
                notFoundCount = 1,
                totalCount = 3,
                search = ReviewSearchState(
                    itemId = 2,
                    query = "Artiste inconnu Un titre très rare",
                    hasSearched = true,
                    results = listOf(pt("s1", "Un titre très rare", "Artiste inconnu"), pt("s2", "Un titre très rare (Remix)", "DJ Autre")),
                ),
            ),
            actions = ImportReviewActions(),
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ImportReviewEmptyPreview() {
    SpautifailleTheme(dynamicColor = false) {
        ImportReviewScreen(
            state = ImportReviewUiState(isLoading = false, job = PreviewJob.copy(state = ImportJobState.COMPLETED), totalCount = 3),
            actions = ImportReviewActions(),
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

// endregion
