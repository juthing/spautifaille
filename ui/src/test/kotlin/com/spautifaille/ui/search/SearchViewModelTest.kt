package com.spautifaille.ui.search

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.Paged
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private class Token(val id: Int) : PageToken

    private val stream = mockk<StreamRepository>()
    private val playback = mockk<PlaybackController>(relaxed = true) {
        every { state } returns MutableStateFlow(PlayerState())
    }
    private lateinit var viewModel: SearchViewModel

    private fun track(i: Int) = Track(id = "id$i", title = "Titre $i", artist = "Artiste")
    private fun results(range: IntRange) = range.map { SearchResult.TrackResult(track(it)) }

    @Before
    fun setUp() {
        viewModel = SearchViewModel(stream, playback)
    }

    /** `uiState` est en WhileSubscribed : on le collecte pendant le test. */
    private fun TestScope.collectState() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    @Test
    fun `les suggestions sont debouncees et ne portent que sur la derniere saisie`() = runTest {
        collectState()
        coEvery { stream.suggestions(any()) } returns listOf("hello world")

        viewModel.onQueryChange("h")
        advanceTimeBy(100)
        viewModel.onQueryChange("he")
        advanceTimeBy(100)
        viewModel.onQueryChange("hel")
        advanceTimeBy(SearchViewModel.SUGGESTIONS_DEBOUNCE_MS - 1)
        runCurrent()
        coVerify(exactly = 0) { stream.suggestions(any()) }

        advanceTimeBy(2)
        runCurrent()
        coVerify(exactly = 1) { stream.suggestions("hel") }
        assertEquals(listOf("hello world"), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `une saisie vide efface les suggestions sans appel reseau`() = runTest {
        collectState()
        coEvery { stream.suggestions(any()) } returns listOf("a")
        viewModel.onQueryChange("a")
        advanceUntilIdle()
        assertEquals(listOf("a"), viewModel.uiState.value.suggestions)

        viewModel.onQueryChange("")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
        coVerify(exactly = 1) { stream.suggestions(any()) }
    }

    @Test
    fun `un echec des suggestions est ignore`() = runTest {
        collectState()
        coEvery { stream.suggestions(any()) } throws AppException(AppError.Network)
        viewModel.onQueryChange("abc")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `une recherche reussie remplit les resultats`() = runTest {
        collectState()
        coEvery { stream.search("daft punk", SearchFilter.SONGS, null) } returns Paged(results(1..3), next = null)

        viewModel.onQueryChange("  daft punk ")
        viewModel.onSearch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("daft punk", state.submittedQuery)
        assertEquals(3, state.results.size)
        assertFalse(state.hasMore)
        assertNull(state.error)
        assertEquals(listOf("daft punk"), state.recentQueries)
    }

    @Test
    fun `une recherche en echec expose l erreur puis retry la relance`() = runTest {
        collectState()
        coEvery { stream.search("q", SearchFilter.SONGS, null) } throws AppException(AppError.Network)

        viewModel.onSearch("q")
        advanceUntilIdle()
        assertEquals(AppError.Network, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.results.isEmpty())

        coEvery { stream.search("q", SearchFilter.SONGS, null) } returns Paged(results(1..2), next = null)
        viewModel.onRetry()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertEquals(2, viewModel.uiState.value.results.size)
    }

    @Test
    fun `la pagination ajoute les pages suivantes sans doublons`() = runTest {
        collectState()
        val token = Token(1)
        coEvery { stream.search("q", SearchFilter.SONGS, null) } returns Paged(results(1..3), next = token)
        coEvery { stream.search("q", SearchFilter.SONGS, token) } returns Paged(results(3..5), next = null)

        viewModel.onSearch("q")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasMore)

        viewModel.onLoadMore()
        viewModel.onLoadMore() // ignoré : déjà en cours
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("id1", "id2", "id3", "id4", "id5"), state.results.map { (it as SearchResult.TrackResult).track.id })
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
        coVerify(exactly = 1) { stream.search("q", SearchFilter.SONGS, token) }

        viewModel.onLoadMore() // plus de page suivante
        advanceUntilIdle()
        coVerify(exactly = 1) { stream.search("q", SearchFilter.SONGS, token) }
    }

    @Test
    fun `une erreur de page suivante conserve les resultats`() = runTest {
        collectState()
        val token = Token(1)
        coEvery { stream.search("q", SearchFilter.SONGS, null) } returns Paged(results(1..2), next = token)
        coEvery { stream.search("q", SearchFilter.SONGS, token) } throws AppException(AppError.Network)

        viewModel.onSearch("q")
        advanceUntilIdle()
        viewModel.onLoadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.results.size)
        assertEquals(AppError.Network, state.loadMoreError)
        assertNull(state.error)

        coEvery { stream.search("q", SearchFilter.SONGS, token) } returns Paged(results(3..4), next = null)
        viewModel.onRetry()
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.results.size)
        assertNull(viewModel.uiState.value.loadMoreError)
    }

    @Test
    fun `changer de filtre relance la recherche avec le nouveau filtre`() = runTest {
        collectState()
        coEvery { stream.search("q", SearchFilter.SONGS, null) } returns Paged(results(1..2), next = null)
        coEvery { stream.search("q", SearchFilter.ARTISTS, null) } returns Paged(emptyList(), next = null)

        viewModel.onSearch("q")
        advanceUntilIdle()
        viewModel.onFilterSelected(SearchFilter.ARTISTS)
        advanceUntilIdle()

        coVerify(exactly = 1) { stream.search("q", SearchFilter.ARTISTS, null) }
        assertEquals(SearchFilter.ARTISTS, viewModel.uiState.value.filter)
        assertTrue(viewModel.uiState.value.results.isEmpty())
    }

    @Test
    fun `changer de filtre sans recherche soumise ne lance rien`() = runTest {
        collectState()
        viewModel.onFilterSelected(SearchFilter.ALBUMS)
        advanceUntilIdle()
        assertEquals(SearchFilter.ALBUMS, viewModel.uiState.value.filter)
        coVerify(exactly = 0) { stream.search(any(), any(), any()) }
    }

    @Test
    fun `une requete vide est ignoree`() = runTest {
        collectState()
        viewModel.onSearch("   ")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.submittedQuery)
        coVerify(exactly = 0) { stream.search(any(), any(), any()) }
    }

    @Test
    fun `les recherches recentes sont dedoublonnees et plafonnees`() = runTest {
        collectState()
        coEvery { stream.search(any(), any(), any()) } returns Paged(emptyList(), next = null)

        (1..12).forEach { viewModel.onSearch("q$it") }
        viewModel.onSearch("Q5")
        advanceUntilIdle()

        val recents = viewModel.uiState.value.recentQueries
        assertEquals(SearchViewModel.MAX_RECENT_QUERIES, recents.size)
        assertEquals("Q5", recents.first())
        assertEquals(1, recents.count { it.equals("q5", ignoreCase = true) })
    }

    @Test
    fun `cliquer un titre lit la liste des titres a partir de celui-ci`() = runTest {
        collectState()
        coEvery { stream.search("q", SearchFilter.SONGS, null) } returns Paged(results(1..4), next = null)
        viewModel.onSearch("q")
        advanceUntilIdle()

        viewModel.onTrackClick(track(3))

        verify { playback.play(listOf(track(1), track(2), track(3), track(4)), 2, false) }
    }
}
