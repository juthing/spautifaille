package com.spautifaille.ui.importer

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.Paged
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImportReviewViewModelTest {

    @get:Rule
    val mainRule = ImportMainRule()

    private val repository = FakeImportRepository()
    private val stream = mockk<StreamRepository>()

    private fun viewModel(jobId: Long? = 1L) =
        ImportReviewViewModel(
            SavedStateHandle(if (jobId == null) emptyMap() else mapOf("jobId" to jobId)),
            repository,
            stream,
        )

    private val review = item(1, MatchStatus.NEEDS_REVIEW, best = tr("r1"), alternatives = listOf(tr("a1"), tr("a2")))
    private val missing = item(2, MatchStatus.NOT_FOUND)
    private val matched = item(3, MatchStatus.MATCHED, best = tr("m1"), score = 0.95)
    private val pending = item(4, MatchStatus.PENDING)

    private fun seed(vararg items: com.spautifaille.domain.importer.ImportItem) {
        repository.jobs.value = listOf(job(1))
        repository.items.value = mapOf(1L to items.toList())
    }

    @Test
    fun `default filter is needs review, with counts and filtered items`() = runTest {
        seed(review, missing, matched, pending)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(ReviewFilter.NEEDS_REVIEW, state.filter)
        assertEquals(listOf(1L), state.items.map { it.id })
        assertEquals(listOf(1, 1, 4), listOf(state.needsReviewCount, state.notFoundCount, state.totalCount))
    }

    @Test
    fun `default filter falls back to not found then to all`() = runTest {
        seed(missing, matched)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertEquals(ReviewFilter.NOT_FOUND, vm.uiState.value.filter)
        assertEquals(listOf(2L), vm.uiState.value.items.map { it.id })

        seed(matched)
        val other = viewModel()
        collectInBackground(other.uiState)
        assertEquals(ReviewFilter.ALL, other.uiState.value.filter)
    }

    @Test
    fun `filters switch the displayed items and the initial pick is not overridden by later changes`() = runTest {
        seed(review, missing, matched, pending)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.setFilter(ReviewFilter.NOT_FOUND)
        assertEquals(listOf(2L), vm.uiState.value.items.map { it.id })
        vm.setFilter(ReviewFilter.ALL)
        assertEquals(listOf(1L, 2L, 3L, 4L), vm.uiState.value.items.map { it.id })

        // Le dernier « à vérifier » est résolu : l'onglet choisi par l'utilisateur ne bouge pas.
        seed(matched, missing)
        assertEquals(ReviewFilter.ALL, vm.uiState.value.filter)
    }

    @Test
    fun `initial filter stays put when the reviewed items are resolved`() = runTest {
        seed(review, missing)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertEquals(ReviewFilter.NEEDS_REVIEW, vm.uiState.value.filter)

        seed(item(1, MatchStatus.MATCHED, best = tr("r1")), missing)

        assertEquals(ReviewFilter.NEEDS_REVIEW, vm.uiState.value.filter)
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun `job progress updates and identical item lists keep the very same filtered list`() = runTest {
        seed(review, missing, matched)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        val before = vm.uiState.value.items

        // Le job progresse (compteurs) mais les items ne changent pas : la liste affichée n'est pas recalculée.
        repository.jobs.value = listOf(job(1, processed = 5))
        assertSame(before, vm.uiState.value.items)
        assertEquals(5, vm.uiState.value.job?.processed)

        // Même contenu réémis (nouvelle liste égale) : aucune nouvelle liste non plus.
        repository.items.value = mapOf(1L to listOf(review, missing, matched))
        assertSame(before, vm.uiState.value.items)
    }

    @Test
    fun `missing job id or unknown job gives a not found state`() = runTest {
        val vm = viewModel(jobId = null)
        collectInBackground(vm.uiState)
        assertEquals(-1L, vm.jobId)
        assertTrue(vm.uiState.value.isNotFound)

        val unknown = viewModel(jobId = 99L)
        collectInBackground(unknown.uiState)
        assertTrue(unknown.uiState.value.isNotFound)
    }

    @Test
    fun `toggling alternatives expands and collapses one item at a time`() = runTest {
        seed(review, missing)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.toggleAlternatives(1)
        assertEquals(1L, vm.uiState.value.expandedItemId)
        vm.toggleAlternatives(2)
        assertEquals(2L, vm.uiState.value.expandedItemId)
        vm.toggleAlternatives(2)
        assertNull(vm.uiState.value.expandedItemId)
    }

    @Test
    fun `choosing an alternative resolves the item and collapses the panel`() = runTest {
        seed(review)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.toggleAlternatives(1)

        vm.choose(1, tr("a2"))

        assertEquals(listOf(1L to tr("a2")), repository.resolved)
        assertNull(vm.uiState.value.expandedItemId)
    }

    @Test
    fun `excluding resolves the item with a null track`() = runTest {
        seed(review)
        val vm = viewModel()

        vm.exclude(1)

        assertEquals(listOf<Pair<Long, com.spautifaille.domain.model.Track?>>(1L to null), repository.resolved)
    }

    @Test
    fun `a resolution failure emits an event and keeps the panel open`() = runTest {
        seed(review)
        repository.resolveError = AppException(AppError.Network)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.toggleAlternatives(1)

        vm.events.test {
            vm.choose(1, tr("a1"))
            assertEquals(ImportReviewEvent.Failed(UiText.of(R.string.apperror_network)), awaitItem())
        }
        assertEquals(1L, vm.uiState.value.expandedItemId)
    }

    // --- Recherche manuelle -------------------------------------------------------------------------

    @Test
    fun `starting a search prefills the query like the automatic matching`() = runTest {
        seed(missing)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.startSearch(2)

        val search = vm.uiState.value.search!!
        assertEquals(2L, search.itemId)
        assertEquals("Artiste Source 2", search.query)
        assertFalse(search.hasSearched)
    }

    @Test
    fun `submitting a search lists songs and picking one resolves the item and closes the search`() = runTest {
        seed(missing)
        coEvery { stream.search("Beatles Yesterday", SearchFilter.SONGS, any()) } returns
            Paged(listOf(SearchResult.TrackResult(tr("y1")), SearchResult.TrackResult(tr("y2"))), null)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.startSearch(2)

        vm.onSearchQueryChanged("Beatles Yesterday")
        vm.submitSearch()

        val search = vm.uiState.value.search!!
        assertTrue(search.hasSearched)
        assertFalse(search.isSearching)
        assertEquals(listOf("y1", "y2"), search.results.map { it.id })

        vm.choose(2, search.results[1])

        assertEquals(listOf(2L to tr("y2")), repository.resolved)
        assertNull(vm.uiState.value.search)
    }

    @Test
    fun `blank queries are not submitted and search errors are shown inline`() = runTest {
        seed(missing)
        coEvery { stream.search(any(), any(), any()) } throws AppException(AppError.BotDetected)
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.startSearch(2)

        vm.onSearchQueryChanged("   ")
        vm.submitSearch()
        coVerify(exactly = 0) { stream.search(any(), any(), any()) }

        vm.onSearchQueryChanged("titre")
        vm.submitSearch()
        val search = vm.uiState.value.search!!
        assertEquals(AppError.BotDetected, search.error)
        assertFalse(search.isSearching)
        assertTrue(search.results.isEmpty())
    }

    @Test
    fun `closing the search or switching filter drops it`() = runTest {
        seed(review, missing)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.setFilter(ReviewFilter.ALL)
        vm.startSearch(2)
        assertEquals(2L, vm.uiState.value.search?.itemId)
        vm.closeSearch()
        assertNull(vm.uiState.value.search)

        vm.startSearch(2)
        vm.setFilter(ReviewFilter.NOT_FOUND)
        assertNull(vm.uiState.value.search)
    }

    @Test
    fun `opening alternatives closes the search and vice versa`() = runTest {
        seed(review)
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.startSearch(1)
        vm.toggleAlternatives(1)
        assertNull(vm.uiState.value.search)
        assertEquals(1L, vm.uiState.value.expandedItemId)

        vm.startSearch(1)
        assertNull(vm.uiState.value.expandedItemId)
        assertEquals(1L, vm.uiState.value.search?.itemId)
    }
}
