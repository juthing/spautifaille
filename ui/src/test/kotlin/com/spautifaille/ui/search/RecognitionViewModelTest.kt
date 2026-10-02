package com.spautifaille.ui.search

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.recognition.RecognitionProgress
import com.spautifaille.domain.recognition.RecognizeMusicUseCase
import com.spautifaille.domain.recognition.RecognizedTrack
import com.spautifaille.ui.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val track = RecognizedTrack(title = "Papaoutai", artist = "Stromae", album = "Racine carrée", artworkUrl = "https://x/y.jpg")

    private fun viewModel(flow: Flow<RecognitionProgress>): RecognitionViewModel {
        val useCase = mockk<RecognizeMusicUseCase> {
            every { totalMs } returns 12_000L
            every { this@mockk.invoke() } returns flow
        }
        return RecognitionViewModel(useCase)
    }

    @Test
    fun `ecoute puis resultat, avec un evenement de recherche`() = runTest {
        val vm = viewModel(
            flow {
                emit(RecognitionProgress.Listening(0, 12_000))
                emit(RecognitionProgress.Listening(4_000, 12_000))
                emit(RecognitionProgress.Success(track))
            },
        )
        vm.uiState.test {
            assertEquals(RecognitionUiState.Idle, awaitItem())
            vm.start()
            assertEquals(RecognitionUiState.Listening(0, 12_000), awaitItem())
            assertEquals(RecognitionUiState.Listening(4_000, 12_000), awaitItem())
            assertEquals(RecognitionUiState.Found(track), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        vm.events.test {
            assertEquals(RecognitionEvent.SearchFor("Papaoutai Stromae"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `identification puis aucun titre reconnu`() = runTest {
        val vm = viewModel(
            flow {
                emit(RecognitionProgress.Listening(12_000, 12_000))
                emit(RecognitionProgress.Identifying)
                emit(RecognitionProgress.NoMatch)
            },
        )
        vm.uiState.test {
            assertEquals(RecognitionUiState.Idle, awaitItem())
            vm.start()
            assertEquals(RecognitionUiState.Listening(0, 12_000), awaitItem())
            assertEquals(RecognitionUiState.Listening(12_000, 12_000), awaitItem())
            assertEquals(RecognitionUiState.Identifying, awaitItem())
            assertEquals(RecognitionUiState.NoMatch, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Aucun événement de recherche quand rien n'est reconnu.
        vm.events.test { expectNoEvents() }
    }

    @Test
    fun `une erreur de la reconnaissance devient un etat d'echec`() = runTest {
        val vm = viewModel(flow { throw AppException(AppError.Network) })
        vm.start()
        advanceUntilIdle()
        assertEquals(RecognitionUiState.Failed(AppError.Network), vm.uiState.value)

        val micVm = viewModel(flow { throw AppException(AppError.MicrophoneUnavailable) })
        micVm.start()
        advanceUntilIdle()
        assertEquals(RecognitionUiState.Failed(AppError.MicrophoneUnavailable), micVm.uiState.value)

        val unexpected = viewModel(flow { throw IllegalStateException("boom") })
        unexpected.start()
        advanceUntilIdle()
        assertTrue((unexpected.uiState.value as RecognitionUiState.Failed).error is AppError.Unknown)
    }

    @Test
    fun `refus de permission, definitif ou non`() = runTest {
        val vm = viewModel(flow { })
        vm.onPermissionDenied(permanentlyDenied = false)
        assertEquals(RecognitionUiState.PermissionDenied(false), vm.uiState.value)
        vm.onPermissionDenied(permanentlyDenied = true)
        assertEquals(RecognitionUiState.PermissionDenied(true), vm.uiState.value)
        vm.dismiss()
        assertEquals(RecognitionUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `annuler arrete l'ecoute et ferme la feuille`() = runTest {
        var cancelled = false
        val vm = viewModel(
            flow {
                emit(RecognitionProgress.Listening(0, 12_000))
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
        )
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is RecognitionUiState.Listening)

        vm.dismiss()
        advanceUntilIdle()

        assertTrue("la capture doit être annulée", cancelled)
        assertEquals(RecognitionUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `passer en arriere-plan coupe l'ecoute mais garde un resultat affiche`() = runTest {
        val listening = viewModel(
            flow {
                emit(RecognitionProgress.Listening(0, 12_000))
                awaitCancellation()
            },
        )
        listening.start()
        advanceUntilIdle()
        listening.onAppStopped()
        assertEquals(RecognitionUiState.Idle, listening.uiState.value)

        val found = viewModel(flow { emit(RecognitionProgress.Success(track)) })
        found.start()
        advanceUntilIdle()
        found.onAppStopped()
        assertEquals(RecognitionUiState.Found(track), found.uiState.value)
    }

    @Test
    fun `relancer apres un echec redemarre l'ecoute`() = runTest {
        var calls = 0
        val vm = viewModel(
            flow {
                calls++
                if (calls == 1) throw AppException(AppError.Network)
                emit(RecognitionProgress.Success(track))
            },
        )
        vm.start()
        advanceUntilIdle()
        assertEquals(RecognitionUiState.Failed(AppError.Network), vm.uiState.value)

        vm.start()
        advanceUntilIdle()
        assertEquals(RecognitionUiState.Found(track), vm.uiState.value)
    }
}
