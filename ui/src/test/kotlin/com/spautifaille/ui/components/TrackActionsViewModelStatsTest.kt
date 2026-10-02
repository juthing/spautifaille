package com.spautifaille.ui.components

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.TrackStats
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.library.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class TrackActionsViewModelStatsTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val streams = mockk<StreamRepository>()

    private fun viewModel() = TrackActionsViewModel(
        playbackController = mockk(relaxed = true),
        libraryRepository = mockk(relaxed = true),
        playlistRepository = mockk(relaxed = true) { every { observePlaylists() } returns flowOf(emptyList()) },
        downloadRepository = mockk(relaxed = true),
        messenger = mockk(relaxed = true),
        notificationPermission = mockk(relaxed = true),
        streamRepository = streams,
    )

    @Test
    fun `charge les statistiques a la selection`() = runTest {
        val stats = TrackStats(1_200_000, 35_000, LocalDate.of(2021, 3, 12))
        coEvery { streams.trackStats("a") } returns stats
        val vm = viewModel()
        vm.select("a")
        advanceUntilIdle()
        assertEquals(TrackStatsState.Loaded(stats), vm.stats.value)
    }

    @Test
    fun `masque la ligne en cas d erreur`() = runTest {
        coEvery { streams.trackStats("a") } throws AppException(AppError.Network)
        val vm = viewModel()
        vm.select("a")
        advanceUntilIdle()
        assertEquals(TrackStatsState.Hidden, vm.stats.value)
    }

    @Test
    fun `masque la ligne quand aucune valeur n est connue`() = runTest {
        coEvery { streams.trackStats("a") } returns TrackStats()
        val vm = viewModel()
        vm.select("a")
        advanceUntilIdle()
        assertEquals(TrackStatsState.Hidden, vm.stats.value)
    }
}
