package com.spautifaille.ui.importer

import app.cash.turbine.test
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.NotificationPermissionRequester
import com.spautifaille.ui.common.UiText
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImportViewModelTest {

    @get:Rule
    val mainRule = ImportMainRule()

    private val repository = FakeImportRepository()
    private val stream = mockk<StreamRepository> {
        every { isPlaylistUrl(any()) } answers { firstArg<String>().contains("list=") }
    }
    private var fileInfo = ImportFileInfo("export.csv", "text/csv")
    private val fileInfoProvider = object : ImportFileInfoProvider {
        override suspend fun describe(uri: String) = fileInfo
    }

    private val notificationPermission = mockk<NotificationPermissionRequester>(relaxed = true)

    private fun viewModel() = ImportViewModel(repository, stream, fileInfoProvider, notificationPermission)

    private val validUrl = "https://music.youtube.com/playlist?list=PL123"

    @Test
    fun `initial state is loading then shows an empty job list`() = runTest {
        val vm = viewModel()
        assertTrue(vm.uiState.value.isLoadingJobs)
        collectInBackground(vm.uiState)
        assertFalse(vm.uiState.value.isLoadingJobs)
        assertTrue(vm.uiState.value.jobs.isEmpty())
    }

    @Test
    fun `starting an import asks for the notification permission`() = runTest {
        repository.onStart = { listOf(1L) }
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.onFilePicked("content://export.csv")

        verify(exactly = 1) { notificationPermission.requestIfNeeded() }
    }

    @Test
    fun `an invalid url does not ask for the notification permission`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.onUrlChanged("https://exemple.fr/page")

        vm.importUrl()

        verify(exactly = 0) { notificationPermission.requestIfNeeded() }
    }

    @Test
    fun `url validation follows StreamRepository isPlaylistUrl`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertFalse(vm.uiState.value.urlInvalid)
        assertFalse(vm.uiState.value.canImportUrl)

        vm.onUrlChanged("https://exemple.fr/page")
        assertTrue(vm.uiState.value.urlInvalid)
        assertFalse(vm.uiState.value.canImportUrl)

        vm.onUrlChanged("  $validUrl  ")
        assertFalse(vm.uiState.value.urlInvalid)
        assertTrue(vm.uiState.value.canImportUrl)

        vm.onUrlChanged("   ")
        assertFalse(vm.uiState.value.urlInvalid)
        assertFalse(vm.uiState.value.canImportUrl)
    }

    @Test
    fun `importUrl starts the import with the trimmed url, clears the field and emits Started`() = runTest {
        repository.onStart = { listOf(7L, 8L) }
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.onUrlChanged("  $validUrl ")

        vm.events.test {
            vm.importUrl()
            assertEquals(ImportEvent.Started(2), awaitItem())
        }

        assertEquals(listOf<ImportSource>(ImportSource.Url(validUrl)), repository.started)
        assertEquals("", vm.uiState.value.url)
        assertFalse(vm.uiState.value.isStarting)
    }

    @Test
    fun `importUrl ignores an invalid url`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.onUrlChanged("https://exemple.fr")

        vm.importUrl()

        assertTrue(repository.started.isEmpty())
        assertEquals("https://exemple.fr", vm.uiState.value.url)
    }

    @Test
    fun `import failure keeps the url and surfaces a readable error`() = runTest {
        repository.onStart = { throw AppException(AppError.Unknown("Cette playlist est vide ou n'est pas accessible.")) }
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.onUrlChanged(validUrl)

        vm.events.test {
            vm.importUrl()
            assertEquals(ImportEvent.Failed(UiText.Plain("Cette playlist est vide ou n'est pas accessible.")), awaitItem())
        }

        assertEquals(validUrl, vm.uiState.value.url)
        assertFalse(vm.uiState.value.isStarting)
        assertTrue(vm.uiState.value.canImportUrl)
    }

    @Test
    fun `network and unexpected errors map to standard messages`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.onUrlChanged(validUrl)

        vm.events.test {
            repository.onStart = { throw AppException(AppError.Network) }
            vm.importUrl()
            assertEquals(ImportEvent.Failed(UiText.of(R.string.apperror_network)), awaitItem())

            repository.onStart = { throw IllegalStateException("boom") }
            vm.importUrl()
            assertEquals(ImportEvent.Failed(UiText.of(R.string.import_error_generic)), awaitItem())
        }
    }

    @Test
    fun `isStarting is true while the source is being read and a second start is ignored`() = runTest {
        val gate = CompletableDeferred<List<Long>>()
        repository.onStart = { gate.await() }
        val vm = viewModel()
        collectInBackground(vm.uiState)
        vm.onUrlChanged(validUrl)

        vm.importUrl()
        assertTrue(vm.uiState.value.isStarting)
        assertFalse(vm.uiState.value.canImportUrl)
        vm.onFilePicked("content://x/1")
        vm.importUrl()
        assertEquals(1, repository.started.size)

        gate.complete(listOf(1L))
        assertFalse(vm.uiState.value.isStarting)
    }

    @Test
    fun `picked file is described then imported with its name and mime type`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.events.test {
            vm.onFilePicked("content://docs/42")
            assertEquals(ImportEvent.Started(1), awaitItem())
        }

        assertEquals(
            listOf<ImportSource>(ImportSource.File("content://docs/42", "export.csv", "text/csv")),
            repository.started,
        )
    }

    @Test
    fun `file import failure is reported`() = runTest {
        repository.onStart = { throw AppException(AppError.Unknown("Format de fichier non reconnu.")) }
        val vm = viewModel()

        vm.events.test {
            vm.onFilePicked("content://docs/1")
            assertEquals(ImportEvent.Failed(UiText.Plain("Format de fichier non reconnu.")), awaitItem())
        }
    }

    @Test
    fun `jobs come from the repository and can be deleted`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        repository.jobs.value = listOf(job(2, ImportJobState.RUNNING, total = 10, processed = 5), job(1))
        assertEquals(listOf(2L, 1L), vm.uiState.value.jobs.map { it.id })
        assertEquals(0.5f, vm.uiState.value.jobs.first().progress, 0.001f)

        vm.deleteJob(2)
        assertEquals(listOf(2L), repository.deleted)
    }
}
