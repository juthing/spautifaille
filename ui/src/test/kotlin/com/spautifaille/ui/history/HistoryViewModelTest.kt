package com.spautifaille.ui.history

import com.spautifaille.domain.model.HistoryEntry
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.OfflineAvailability
import com.spautifaille.ui.network.NetworkMonitor
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import com.spautifaille.ui.library.track
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class HistoryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 9, 28)

    private val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val playerState = MutableStateFlow(PlayerState())
    private val library = mockk<LibraryRepository>(relaxed = true) {
        every { observeHistory(any()) } returns history
    }
    private val playback = mockk<PlaybackController>(relaxed = true) {
        every { state } returns playerState
    }

    private val online = MutableStateFlow(true)
    private val network = object : NetworkMonitor {
        override val isUnmetered: Flow<Boolean> = online
        override val isOnline: Flow<Boolean> = online
    }
    private val cachedIds = MutableStateFlow<Set<String>>(emptySet())
    private val offlineAvailability = object : OfflineAvailability {
        override fun observePlayableIds(): Flow<Set<String>> = cachedIds
    }

    private fun viewModel() = HistoryViewModel(library, playback, network, offlineAvailability, { today }, { zone })

    private fun entry(id: Long, date: LocalDate, hour: Int = 12) = HistoryEntry(
        id = id,
        track = track(id.toInt()),
        playedAt = LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli(),
    )

    @Test
    fun `initial state is loading`() {
        assertTrue(viewModel().uiState.value.isLoading)
    }

    @Test
    fun `empty history is loaded with no items`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.entries.isEmpty())
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `entries are grouped by day with headers`() = runTest {
        history.value = listOf(entry(1, today, 20), entry(2, today, 9), entry(3, today.minusDays(1)), entry(4, today.minusDays(5)))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val items = vm.uiState.value.items
        val headers = items.filterIsInstance<HistoryListItem.Header>().map { it.day }
        assertEquals(listOf(HistoryDay.Today, HistoryDay.Yesterday, HistoryDay.On(today.minusDays(5))), headers)
        assertEquals(4, items.filterIsInstance<HistoryListItem.Entry>().size)
        assertEquals(4, vm.uiState.value.entries.size)
    }

    @Test
    fun `current track and playing flag come from the player`() = runTest {
        history.value = listOf(entry(1, today))
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertNull(vm.uiState.value.currentTrackId)

        playerState.value = PlayerState(currentTrack = track(1), isPlaying = true)

        assertEquals("video1", vm.uiState.value.currentTrackId)
        assertTrue(vm.uiState.value.isPlaying)
    }

    @Test
    fun `playFrom plays the chosen entry then the older ones`() = runTest {
        history.value = listOf(entry(1, today), entry(2, today), entry(3, today))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playFrom(1)

        verify { playback.play(listOf(track(2), track(3)), 0, false) }
    }

    @Test
    fun `playFrom past the end does nothing`() = runTest {
        history.value = listOf(entry(1, today))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playFrom(5)

        verify(exactly = 0) { playback.play(any(), any(), any()) }
    }

    @Test
    fun `offline playFrom only queues downloaded or cached tracks`() = runTest {
        history.value = listOf(entry(1, today), entry(2, today), entry(3, today), entry(4, today))
        cachedIds.value = setOf("video2", "video4")
        online.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.uiState.value.isOffline)
        assertFalse(vm.uiState.value.isAvailable("video1"))
        assertTrue(vm.uiState.value.isAvailable("video2"))
        vm.playFrom(1)

        verify { playback.play(listOf(track(2), track(4)), 0, false) }
    }

    @Test
    fun `offline playFrom on an unplayable entry does nothing`() = runTest {
        history.value = listOf(entry(1, today), entry(2, today))
        cachedIds.value = setOf("video2")
        online.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.playFrom(0)

        verify(exactly = 0) { playback.play(any(), any(), any(), any()) }
    }

    @Test
    fun `every entry is available online`() = runTest {
        history.value = listOf(entry(1, today))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.uiState.value.isAvailable("video1"))
    }

    @Test
    fun `remove deletes one entry`() = runTest {
        val vm = viewModel()
        vm.remove(42)
        coVerify { library.removeHistoryEntry(42) }
    }

    @Test
    fun `clear empties the whole history`() = runTest {
        val vm = viewModel()
        vm.clear()
        coVerify { library.clearHistory() }
    }
}
