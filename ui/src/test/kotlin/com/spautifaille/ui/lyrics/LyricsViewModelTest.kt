package com.spautifaille.ui.lyrics

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.lyrics.LyricLine
import com.spautifaille.domain.lyrics.Lyrics
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.player.PlaybackPosition
import com.spautifaille.domain.player.PlayerState
import com.spautifaille.domain.repository.LyricsRepository
import com.spautifaille.ui.MainDispatcherRule
import com.spautifaille.ui.common.ElapsedClock
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val track = Track(id = "abc", title = "Titre", artist = "Artiste")
    private val stateFlow = MutableStateFlow(PlayerState(currentTrack = track, isPlaying = false))
    private val positionFlow = MutableStateFlow(PlaybackPosition())
    private lateinit var controller: PlaybackController
    private lateinit var repository: LyricsRepository

    private val synced = Lyrics.Synced(
        listOf(
            LyricLine(1_000, "a"),
            LyricLine(2_000, "b"),
            LyricLine(3_000, "c"),
        ),
    )

    @Before
    fun setUp() {
        controller = mockk(relaxed = true) {
            every { state } returns stateFlow
            every { position } returns positionFlow
        }
        repository = mockk()
        coEvery { repository.lyrics(any()) } returns synced
    }

    private fun TestScope.viewModel() = LyricsViewModel(controller, repository, ElapsedClock { testScheduler.currentTime })

    private fun position(ms: Long) = PlaybackPosition(positionMs = ms, durationMs = 10_000)

    @Test
    fun `chargement puis paroles synchronisees avec la ligne selon la position`() = runTest {
        positionFlow.value = position(2_500)
        viewModel().uiState.test {
            assertEquals(LyricsUiState.Loading, awaitItem())
            val synced = awaitItem() as LyricsUiState.Synced
            assertEquals(1, synced.activeIndex)
            assertEquals("LRCLIB", synced.source)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `avant la premiere ligne l index vaut -1 et une longue introduction devient une pause`() = runTest {
        coEvery { repository.lyrics(any()) } returns Lyrics.Synced(listOf(LyricLine(10_000, "a"), LyricLine(12_000, "b")))
        positionFlow.value = position(1_000)
        viewModel().uiState.test {
            assertEquals(LyricsUiState.Loading, awaitItem())
            val state = awaitItem() as LyricsUiState.Synced
            // Pause d'introduction ajoutée à 0 : c'est elle la ligne courante.
            assertEquals(0, state.activeIndex)
            assertTrue(state.lines.first().isBreak)
            assertEquals(3, state.lines.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `avant la premiere ligne sans introduction l index vaut -1`() = runTest {
        positionFlow.value = position(0)
        viewModel().uiState.test {
            awaitItem()
            assertEquals(-1, (awaitItem() as LyricsUiState.Synced).activeIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `les pauses courtes sont masquees`() = runTest {
        coEvery { repository.lyrics(any()) } returns Lyrics.Synced(
            listOf(LyricLine(1_000, "a"), LyricLine(2_000, ""), LyricLine(3_000, "b")),
        )
        viewModel().uiState.test {
            awaitItem()
            assertEquals(listOf("a", "b"), (awaitItem() as LyricsUiState.Synced).lines.map { it.text })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `la ligne courante avance avec l horloge entre deux positions quand ca joue`() = runTest {
        stateFlow.value = PlayerState(currentTrack = track, isPlaying = true)
        positionFlow.value = position(500)
        viewModel().uiState.test {
            assertEquals(LyricsUiState.Loading, awaitItem())
            assertEquals(-1, (awaitItem() as LyricsUiState.Synced).activeIndex)
            // 500 ms -> la ligne « a » (1 000 ms) démarre exactement 500 ms plus tard, sans nouvelle position.
            advanceTimeBy(499)
            expectNoEvents()
            advanceTimeBy(2)
            assertEquals(0, (awaitItem() as LyricsUiState.Synced).activeIndex)
            advanceTimeBy(1_000)
            assertEquals(1, (awaitItem() as LyricsUiState.Synced).activeIndex)
            advanceTimeBy(1_000)
            assertEquals(2, (awaitItem() as LyricsUiState.Synced).activeIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `la vitesse de lecture accelere le changement de ligne`() = runTest {
        stateFlow.value = PlayerState(currentTrack = track, isPlaying = true, speed = 2f)
        positionFlow.value = position(0)
        viewModel().uiState.test {
            awaitItem()
            assertEquals(-1, (awaitItem() as LyricsUiState.Synced).activeIndex)
            advanceTimeBy(501) // 1 000 ms de musique à 2x
            assertEquals(0, (awaitItem() as LyricsUiState.Synced).activeIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en pause la ligne ne change pas`() = runTest {
        positionFlow.value = position(1_500)
        viewModel().uiState.test {
            awaitItem()
            assertEquals(0, (awaitItem() as LyricsUiState.Synced).activeIndex)
            advanceTimeBy(10_000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `un seek ou une nouvelle position met a jour la ligne`() = runTest {
        positionFlow.value = position(1_500)
        viewModel().uiState.test {
            awaitItem()
            assertEquals(0, (awaitItem() as LyricsUiState.Synced).activeIndex)
            positionFlow.value = position(3_200)
            assertEquals(2, (awaitItem() as LyricsUiState.Synced).activeIndex)
            positionFlow.value = position(1_100)
            assertEquals(0, (awaitItem() as LyricsUiState.Synced).activeIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paroles non synchronisees`() = runTest {
        coEvery { repository.lyrics(any()) } returns Lyrics.Plain("texte", "LRCLIB")
        viewModel().uiState.test {
            awaitItem()
            assertEquals(LyricsUiState.Plain("texte", "LRCLIB"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `instrumental`() = runTest {
        coEvery { repository.lyrics(any()) } returns Lyrics.Instrumental("LRCLIB")
        viewModel().uiState.test {
            awaitItem()
            assertEquals(LyricsUiState.Instrumental("LRCLIB"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `introuvable`() = runTest {
        coEvery { repository.lyrics(any()) } returns null
        viewModel().uiState.test {
            awaitItem()
            assertEquals(LyricsUiState.NotFound, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lrc sans texte equivaut a introuvable`() = runTest {
        coEvery { repository.lyrics(any()) } returns Lyrics.Synced(listOf(LyricLine(0, "")))
        viewModel().uiState.test {
            awaitItem()
            assertEquals(LyricsUiState.NotFound, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pas de titre en cours`() = runTest {
        stateFlow.value = PlayerState(currentTrack = null)
        viewModel().uiState.test {
            assertEquals(LyricsUiState.Loading, awaitItem())
            assertEquals(LyricsUiState.NotFound, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `erreur reseau puis nouvel essai`() = runTest {
        coEvery { repository.lyrics(any()) } throws AppException(AppError.Network) andThen synced
        val vm = viewModel()
        vm.uiState.test {
            assertEquals(LyricsUiState.Loading, awaitItem())
            assertEquals(LyricsUiState.Error(AppError.Network), awaitItem())

            vm.retry()
            assertEquals(LyricsUiState.Loading, awaitItem())
            assertTrue(awaitItem() is LyricsUiState.Synced)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { repository.lyrics(track) }
    }

    @Test
    fun `exception inattendue convertie en erreur inconnue`() = runTest {
        coEvery { repository.lyrics(any()) } throws IllegalStateException("boom")
        viewModel().uiState.test {
            awaitItem()
            val error = awaitItem() as LyricsUiState.Error
            assertTrue(error.error is AppError.Unknown)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `un changement de titre recharge les paroles`() = runTest {
        val other = Track(id = "def", title = "Autre", artist = "Artiste")
        coEvery { repository.lyrics(track) } returns synced
        coEvery { repository.lyrics(other) } returns Lyrics.Plain("autre texte")
        viewModel().uiState.test {
            awaitItem()
            assertTrue(awaitItem() is LyricsUiState.Synced)

            stateFlow.value = PlayerState(currentTrack = other)
            assertEquals(LyricsUiState.Loading, awaitItem())
            assertEquals(LyricsUiState.Plain("autre texte", "LRCLIB"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `un changement d etat du lecteur sur le meme titre ne recharge pas`() = runTest {
        viewModel().uiState.test {
            awaitItem()
            awaitItem()
            stateFlow.value = PlayerState(currentTrack = track, isPlaying = false, isCurrentLiked = true)
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repository.lyrics(any()) }
    }

    @Test
    fun `seek delegue au controleur`() = runTest {
        viewModel().seekTo(12_345)
        verify { controller.seekTo(12_345) }
    }
}
