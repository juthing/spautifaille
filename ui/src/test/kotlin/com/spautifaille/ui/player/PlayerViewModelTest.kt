package com.spautifaille.ui.player

import app.cash.turbine.test
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.player.SleepTimer
import com.spautifaille.ui.MainDispatcherRule
import com.spautifaille.ui.R
import com.spautifaille.ui.common.ElapsedClock
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val stateFlow = MutableStateFlow(PlayerState())
    private val positionFlow = MutableStateFlow(PlaybackPosition())
    private val messenger = UiMessenger()
    private lateinit var controller: PlaybackController
    private lateinit var viewModel: PlayerViewModel

    private val track = Track(id = "abc", title = "Titre", artist = "Artiste")

    @Before
    fun setUp() {
        controller = mockk(relaxed = true) {
            every { state } returns stateFlow
            every { position } returns positionFlow
        }
        viewModel = PlayerViewModel(controller, messenger, ElapsedClock { 0L })
    }

    /** Horloge `elapsedRealtime` simulée : suit le temps virtuel du test. */
    private fun TestScope.virtualClock() = ElapsedClock { testScheduler.currentTime }

    @Test
    fun `l etat et la position refletent ceux du controleur`() {
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

    // region Minuterie de sommeil

    @Test
    fun `le temps restant decroit chaque seconde depuis l echeance et non depuis remainingMs`() = runTest {
        // `remainingMs` est périmé (publié une seule fois par le lecteur) : seule l'échéance compte.
        stateFlow.value = PlayerState(sleepTimer = SleepTimer.At(endsAtElapsedMs = 5_000, remainingMs = 999_999))
        val vm = PlayerViewModel(controller, messenger, virtualClock())

        vm.sleepTimerRemaining.test {
            assertEquals(5_000L, awaitItem())

            advanceTimeBy(1_001)
            assertEquals(4_000L, awaitItem())
            advanceTimeBy(1_000)
            assertEquals(3_000L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `le decompte s arrete a zero`() = runTest {
        stateFlow.value = PlayerState(sleepTimer = SleepTimer.At(endsAtElapsedMs = 2_000, remainingMs = 2_000))
        val vm = PlayerViewModel(controller, messenger, virtualClock())

        vm.sleepTimerRemaining.test {
            assertEquals(2_000L, awaitItem())
            advanceTimeBy(1_001)
            assertEquals(1_000L, awaitItem())
            advanceTimeBy(1_000)
            assertEquals(0L, awaitItem())

            // Plus aucun tick une fois à zéro.
            advanceTimeBy(10_000)
            expectNoEvents()
        }
    }

    @Test
    fun `sans minuterie chronometree le flux vaut null et aucun tick n est emis`() = runTest {
        val vm = PlayerViewModel(controller, messenger, virtualClock())

        vm.sleepTimerRemaining.test {
            assertNull(awaitItem())
            advanceTimeBy(5_000)
            expectNoEvents()

            stateFlow.value = PlayerState(sleepTimer = SleepTimer.EndOfTrack)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `annuler la minuterie remet le temps restant a null et arrete le ticker`() = runTest {
        stateFlow.value = PlayerState(sleepTimer = SleepTimer.At(endsAtElapsedMs = 60_000, remainingMs = 60_000))
        val vm = PlayerViewModel(controller, messenger, virtualClock())

        vm.sleepTimerRemaining.test {
            assertEquals(60_000L, awaitItem())

            stateFlow.value = PlayerState(sleepTimer = SleepTimer.Off)
            assertNull(awaitItem())
            advanceTimeBy(5_000)
            expectNoEvents()
        }
    }

    @Test
    fun `une nouvelle minuterie repart de sa propre echeance`() = runTest {
        stateFlow.value = PlayerState(sleepTimer = SleepTimer.At(endsAtElapsedMs = 60_000, remainingMs = 60_000))
        val vm = PlayerViewModel(controller, messenger, virtualClock())

        vm.sleepTimerRemaining.test {
            assertEquals(60_000L, awaitItem())
            advanceTimeBy(10_001)
            assertEquals(50_000L, expectMostRecentItem())

            stateFlow.value = PlayerState(sleepTimer = SleepTimer.At(endsAtElapsedMs = 20_000, remainingMs = 10_000))
            assertEquals(9_999L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion

    // region Retour du like

    @Test
    fun `un like affiche la confirmation une fois le nouvel etat observe`() = runTest {
        stateFlow.value = PlayerState(currentTrack = track, isCurrentLiked = false)

        messenger.messages.test {
            viewModel.toggleLike()
            runCurrent()
            expectNoEvents() // rien tant que le lecteur n'a pas publié le nouvel état

            stateFlow.value = stateFlow.value.copy(isCurrentLiked = true)
            assertEquals(UiText.of(R.string.snack_liked), awaitItem())
        }
        verify(exactly = 1) { controller.toggleLikeCurrent() }
    }

    @Test
    fun `un unlike affiche la confirmation de retrait`() = runTest {
        stateFlow.value = PlayerState(currentTrack = track, isCurrentLiked = true)

        messenger.messages.test {
            viewModel.toggleLike()
            runCurrent()
            stateFlow.value = stateFlow.value.copy(isCurrentLiked = false)
            assertEquals(UiText.of(R.string.snack_unliked), awaitItem())
        }
    }

    @Test
    fun `un changement de titre apres le like n affiche rien`() = runTest {
        stateFlow.value = PlayerState(currentTrack = track, isCurrentLiked = false)

        messenger.messages.test {
            viewModel.toggleLike()
            runCurrent()

            // Le titre suivant est déjà aimé : ce n'est pas la conséquence du toucher.
            stateFlow.value = PlayerState(currentTrack = track.copy(id = "autre"), isCurrentLiked = true)
            runCurrent()
            advanceTimeBy(10_000)
            expectNoEvents()
        }
    }

    @Test
    fun `sans changement d etat la confirmation n est jamais affichee`() = runTest {
        stateFlow.value = PlayerState(currentTrack = track, isCurrentLiked = false)

        messenger.messages.test {
            viewModel.toggleLike()
            advanceTimeBy(10_000)
            stateFlow.value = stateFlow.value.copy(isCurrentLiked = true) // bien après le délai d'attente
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `sans titre courant le like est delegue sans message`() = runTest {
        messenger.messages.test {
            viewModel.toggleLike()
            runCurrent()
            expectNoEvents()
        }
        verify(exactly = 1) { controller.toggleLikeCurrent() }
    }

    // endregion
}
