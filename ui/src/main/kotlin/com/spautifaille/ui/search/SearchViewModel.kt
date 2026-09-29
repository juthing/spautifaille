package com.spautifaille.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.common.toAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Filtres proposés à l'utilisateur, dans l'ordre d'affichage. */
val SearchFilters: List<SearchFilter> = listOf(
    SearchFilter.SONGS,
    SearchFilter.VIDEOS,
    SearchFilter.ALBUMS,
    SearchFilter.PLAYLISTS,
    SearchFilter.ARTISTS,
)

data class SearchUiState(
    /** Texte courant du champ de saisie. */
    val query: String = "",
    /** Requête effectivement lancée (résultats affichés), nulle tant qu'aucune recherche n'a été soumise. */
    val submittedQuery: String? = null,
    val filter: SearchFilter = SearchFilter.SONGS,
    val suggestions: List<String> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    /** Chargement de la première page. */
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** Erreur de la première page (remplace la liste). */
    val error: AppError? = null,
    /** Erreur d'une page suivante (affichée en pied de liste, les résultats restent visibles). */
    val loadMoreError: AppError? = null,
    /** Titre en cours de lecture, pour la mise en évidence. */
    val nowPlayingId: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val playbackController: PlaybackController,
    private val searchHistory: SearchHistoryRepository,
) : ViewModel() {

    private val internal = MutableStateFlow(SearchUiState())
    private val queryInput = MutableStateFlow("")
    private var nextPage: PageToken? = null
    private var searchJob: Job? = null

    val uiState: StateFlow<SearchUiState> = combine(
        internal,
        playbackController.state.map { it.currentTrack?.id }.distinctUntilChanged(),
        searchHistory.queries,
    ) { state, nowPlayingId, recents -> state.copy(nowPlayingId = nowPlayingId, recentQueries = recents) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch {
            queryInput
                .debounce(SUGGESTIONS_DEBOUNCE_MS)
                .map { it.trim() }
                .distinctUntilChanged()
                .mapLatest { query ->
                    if (query.isEmpty()) {
                        emptyList()
                    } else {
                        try {
                            streamRepository.suggestions(query)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Les suggestions sont un confort : on ignore silencieusement l'échec.
                            emptyList()
                        }
                    }
                }
                .collect { suggestions -> internal.update { it.copy(suggestions = suggestions) } }
        }
    }

    fun onQueryChange(query: String) {
        internal.update { it.copy(query = query) }
        queryInput.value = query
    }

    /** Lance la recherche de [query] (par défaut, le texte du champ). */
    fun onSearch(query: String = internal.value.query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        internal.update {
            it.copy(
                query = trimmed,
                submittedQuery = trimmed,
                suggestions = emptyList(),
            )
        }
        viewModelScope.launch { searchHistory.add(trimmed) }
        queryInput.value = trimmed
        runSearch()
    }

    fun onFilterSelected(filter: SearchFilter) {
        if (filter == internal.value.filter) return
        internal.update { it.copy(filter = filter) }
        if (internal.value.submittedQuery != null) runSearch()
    }

    /** « Effacer l'historique ». */
    fun onClearRecent() {
        viewModelScope.launch { searchHistory.clear() }
    }

    /** Retire une seule recherche récente. */
    fun onRemoveRecent(query: String) {
        viewModelScope.launch { searchHistory.remove(query) }
    }

    fun onLoadMore() {
        val state = internal.value
        val token = nextPage
        val query = state.submittedQuery
        if (query == null || token == null || state.isLoading || state.isLoadingMore) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            internal.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            try {
                val page = streamRepository.search(query, state.filter, token)
                nextPage = page.next
                internal.update {
                    it.copy(
                        results = (it.results + page.items).distinctBy(::resultKey),
                        isLoadingMore = false,
                        hasMore = page.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internal.update { it.copy(isLoadingMore = false, loadMoreError = e.toAppError()) }
            }
        }
    }

    /** Relance la première page, ou la page suivante si c'est elle qui avait échoué. */
    fun onRetry() {
        val state = internal.value
        if (state.submittedQuery == null) return
        if (state.error == null && state.loadMoreError != null) onLoadMore() else runSearch()
    }

    /** Lit le titre cliqué en conservant les autres titres de la liste dans la file, à sa suite. */
    fun onTrackClick(track: Track) {
        val tracks = internal.value.results.filterIsInstance<SearchResult.TrackResult>().map { it.track }
        val index = tracks.indexOfFirst { it.id == track.id }
        if (index >= 0) playbackController.play(tracks, startIndex = index) else playbackController.play(listOf(track))
    }

    private fun runSearch() {
        val query = internal.value.submittedQuery ?: return
        val filter = internal.value.filter
        searchJob?.cancel()
        nextPage = null
        internal.update {
            it.copy(
                results = emptyList(),
                isLoading = true,
                isLoadingMore = false,
                hasMore = false,
                error = null,
                loadMoreError = null,
            )
        }
        searchJob = viewModelScope.launch {
            try {
                val page = streamRepository.search(query, filter, null)
                nextPage = page.next
                internal.update {
                    it.copy(
                        results = page.items.distinctBy(::resultKey),
                        isLoading = false,
                        hasMore = page.hasMore,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                internal.update { it.copy(isLoading = false, error = e.toAppError()) }
            }
        }
    }

    private fun resultKey(result: SearchResult): String = when (result) {
        is SearchResult.TrackResult -> "t:" + result.track.id
        is SearchResult.PlaylistResult -> "p:" + result.playlist.url
        is SearchResult.ArtistResult -> "a:" + result.artist.url
    }

    companion object {
        const val SUGGESTIONS_DEBOUNCE_MS = 250L
        const val MAX_RECENT_QUERIES = SearchHistoryRepository.MAX_QUERIES
    }
}
