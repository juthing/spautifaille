package com.spautifaille.ui.player

import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.spautifaille.ui.MainDispatcherRule

class PlayerViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val stateFlow = MutableStateFlow(PlayerState())
    private val positionFlow = MutableStateFlow(PlaybackPosition())
    private lateinit var controller: PlaybackController
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        controller = mockk(relaxed = true) {
            every { state } returns stateFlow
            every { position } returns positionFlow
        }
        viewModel = PlayerViewModel(controller)
    }

    @Test
    fun `l etat et la position refletent ceux du controleur`() {
        val track = Track(id = "abc", title = "Titre", artist = "Artiste")
        stateFlow.value = PlayerState(currentTrack = track, isPlaying = true, durationMs = 1_000)
        positionFlow.value = PlaybackPosition(positionMs = 400, durationMs = 1_000)

        assertEquals(track, viewModel.state.value.currentTrack)
        assertEquals(true, viewModel.state.value.isPlaying)
        assertEquals(400L, viewModel.position.value.positionMs)
    }

    @Test
    fun `les actions de transport sont deleguees`() {
        viewModel.togglePlayPause()
        viewModel.next()
        viewModel.previous()
        viewModel.seekTo(1_234)
        viewModel.cycleRepeatMode()
        viewModel.toggleLike()
        viewModel.setSpeed(1.5f)

        verify(exactly = 1) { controller.togglePlayPause() }
        verify(exactly = 1) { controller.next() }
        verify(exactly = 1) { controller.previous() }
        verify(exactly = 1) { controller.seekTo(1_234) }
        verify(exactly = 1) { controller.cycleRepeatMode() }
        verify(exactly = 1) { controller.toggleLikeCurrent() }
        verify(exactly = 1) { controller.setSpeed(1.5f) }
    }

    @Test
    fun `le shuffle est inverse par rapport a l etat courant`() {
        viewModel.toggleShuffle()
        verify { controller.setShuffle(true) }

        stateFlow.value = PlayerState(shuffleEnabled = true)
        viewModel.toggleShuffle()
        verify { controller.setShuffle(false) }
    }

    @Test
    fun `la minuterie est convertie de minutes en millisecondes`() {
        viewModel.setSleepTimerMinutes(15)
        verify { controller.setSleepTimer(900_000L) }

        viewModel.setSleepTimerEndOfTrack()
        viewModel.cancelSleepTimer()
        verify { controller.setSleepTimerEndOfTrack() }
        verify { controller.cancelSleepTimer() }
    }

    @Test
    fun `les operations de file sont deleguees`() {
        viewModel.skipToQueueItem(3)
        viewModel.moveQueueItem(2, 5)
        viewModel.removeQueueItem(1)
        viewModel.clearQueue()

        verify { controller.skipToQueueItem(3) }
        verify { controller.moveQueueItem(2, 5) }
        verify { controller.removeQueueItem(1) }
        verify { controller.clearQueue() }
    }
}
