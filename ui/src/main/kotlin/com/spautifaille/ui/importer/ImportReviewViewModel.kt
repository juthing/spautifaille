package com.spautifaille.ui.importer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.importer.ImportItem
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportRepository
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.matching.TrackMatcher
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReviewFilter { NEEDS_REVIEW, NOT_FOUND, ALL }

/** Recherche manuelle ouverte sous l'item [itemId]. */
@Immutable
data class ReviewSearchState(
    val itemId: Long,
    val query: String,
    val isSearching: Boolean = false,
    /** Une recherche a abouti : distingue « pas encore lancée » de « aucun résultat ». */
    val hasSearched: Boolean = false,
    val results: List<Track> = emptyList(),
    val error: AppError? = null,
)

@Immutable
data class ImportReviewUiState(
    val isLoading: Boolean = true,
    /** `null` une fois chargé = import supprimé ou introuvable. */
    val job: ImportJob? = null,
    val filter: ReviewFilter = ReviewFilter.NEEDS_REVIEW,
    /** Items du filtre courant, dans l'ordre de la source. */
    val items: List<ImportItem> = emptyList(),
    val needsReviewCount: Int = 0,
    val notFoundCount: Int = 0,
    val totalCount: Int = 0,
    /** Item dont la liste d'alternatives est dépliée. */
    val expandedItemId: Long? = null,
    val search: ReviewSearchState? = null,
) {
    val isNotFound: Boolean get() = !isLoading && job == null
}

sealed interface ImportReviewEvent {
    data class Failed(val message: UiText) : ImportReviewEvent
}

@HiltViewModel
class ImportReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importRepository: ImportRepository,
    private val streamRepository: StreamRepository,
) : ViewModel() {

    /** Argument de navigation `jobId` (Long). `-1` si absent → état « introuvable ». */
    val jobId: Long = savedStateHandle.get<Long>(ARG_JOB_ID) ?: -1L

    private data class Ui(
        val filter: ReviewFilter? = null,
        val expandedItemId: Long? = null,
        val search: ReviewSearchState? = null,
    )

    private val ui = MutableStateFlow(Ui())
    private val matcher = TrackMatcher()
    private var searchJob: Job? = null

    /** Filtre choisi à l'ouverture (premier onglet non vide), figé ensuite pour ne pas sauter sous les doigts. */
    private var initialFilter: ReviewFilter? = null

    private val _events = Channel<ImportReviewEvent>(Channel.BUFFERED)
    val events: Flow<ImportReviewEvent> = _events.receiveAsFlow()

    /** Compteurs d'une liste d'items, recalculés seulement quand la liste change (pas à chaque mise à jour du job). */
    private data class Summary(val items: List<ImportItem>, val needsReview: Int, val notFound: Int)

    // Dernière liste filtrée : réutilisée telle quelle (même instance) tant que ni les items ni le filtre ne changent,
    // pour que les lignes inchangées ne soient ni recalculées ni recomposées lors d'une mise à jour d'une seule ligne.
    private var lastFilteredSource: List<ImportItem>? = null
    private var lastFilteredBy: ReviewFilter? = null
    private var lastFiltered: List<ImportItem> = emptyList()

    val uiState: StateFlow<ImportReviewUiState> = combine(
        importRepository.observeJob(jobId).distinctUntilChanged(),
        importRepository.observeItems(jobId).distinctUntilChanged().map { items ->
            Summary(
                items = items,
                needsReview = items.count { it.result.status == MatchStatus.NEEDS_REVIEW },
                notFound = items.count { it.result.status == MatchStatus.NOT_FOUND },
            )
        },
        ui,
    ) { job, summary, ui ->
        if (initialFilter == null && job != null) {
            initialFilter = when {
                summary.needsReview > 0 -> ReviewFilter.NEEDS_REVIEW
                summary.notFound > 0 -> ReviewFilter.NOT_FOUND
                else -> ReviewFilter.ALL
            }
        }
        val filter = ui.filter ?: initialFilter ?: ReviewFilter.NEEDS_REVIEW
        ImportReviewUiState(
            isLoading = false,
            job = job,
            filter = filter,
            items = filtered(summary.items, filter),
            needsReviewCount = summary.needsReview,
            notFoundCount = summary.notFound,
            totalCount = summary.items.size,
            expandedItemId = ui.expandedItemId,
            search = ui.search,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ImportReviewUiState())

    private fun filtered(items: List<ImportItem>, filter: ReviewFilter): List<ImportItem> {
        if (lastFilteredSource === items && lastFilteredBy == filter) return lastFiltered
        return items.filter { it.matches(filter) }.also {
            lastFilteredSource = items
            lastFilteredBy = filter
            lastFiltered = it
        }
    }

    fun setFilter(filter: ReviewFilter) {
        closeSearch()
        ui.update { it.copy(filter = filter, expandedItemId = null) }
    }

    /** Déplie / replie les alternatives d'un item (ferme la recherche manuelle). */
    fun toggleAlternatives(itemId: Long) {
        closeSearch()
        ui.update { it.copy(expandedItemId = if (it.expandedItemId == itemId) null else itemId) }
    }

    /** Choisit [track] pour l'item (alternative, résultat de recherche ou validation du meilleur candidat). */
    fun choose(itemId: Long, track: Track) = resolve(itemId, track)

    /** Exclut le titre : retiré de la playlist, compté comme introuvable. */
    fun exclude(itemId: Long) = resolve(itemId, null)

    // region Recherche manuelle

    fun startSearch(itemId: Long) {
        val item = uiState.value.items.firstOrNull { it.id == itemId } ?: return
        searchJob?.cancel()
        ui.update { it.copy(expandedItemId = null, search = ReviewSearchState(itemId, initialQuery(item))) }
    }

    fun onSearchQueryChanged(query: String) {
        ui.update { state -> state.search?.let { state.copy(search = it.copy(query = query)) } ?: state }
    }

    fun submitSearch() {
        val current = ui.value.search ?: return
        val query = current.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        ui.update { it.copy(search = current.copy(isSearching = true, error = null)) }
        searchJob = viewModelScope.launch {
            try {
                val tracks = streamRepository.search(query, SearchFilter.SONGS).items
                    .filterIsInstance<SearchResult.TrackResult>().map { it.track }
                updateSearch(current.itemId) { it.copy(isSearching = false, hasSearched = true, results = tracks) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateSearch(current.itemId) { it.copy(isSearching = false, error = e.toAppError()) }
            }
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        ui.update { if (it.search == null) it else it.copy(search = null) }
    }

    private fun updateSearch(itemId: Long, transform: (ReviewSearchState) -> ReviewSearchState) {
        ui.update { state ->
            val search = state.search
            if (search == null || search.itemId != itemId) state else state.copy(search = transform(search))
        }
    }

    /** Requête proposée par défaut : la même que celle du matching automatique. */
    private fun initialQuery(item: ImportItem): String = matcher.buildQuery(item.source).ifBlank { item.source.title }

    // endregion

    private fun resolve(itemId: Long, chosen: Track?) {
        viewModelScope.launch {
            try {
                importRepository.resolveItem(itemId, chosen)
                ui.update { state ->
                    state.copy(
                        expandedItemId = state.expandedItemId.takeUnless { it == itemId },
                        search = state.search?.takeUnless { it.itemId == itemId },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ImportReviewEvent.Failed(e.toImportMessage()))
            }
        }
    }

    private fun ImportItem.matches(filter: ReviewFilter): Boolean = when (filter) {
        ReviewFilter.NEEDS_REVIEW -> result.status == MatchStatus.NEEDS_REVIEW
        ReviewFilter.NOT_FOUND -> result.status == MatchStatus.NOT_FOUND
        ReviewFilter.ALL -> true
    }

    companion object {
        const val ARG_JOB_ID = "jobId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
