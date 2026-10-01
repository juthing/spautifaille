package com.spautifaille.ui.downloads

import app.cash.turbine.test
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.StorageUsage
import com.spautifaille.domain.player.PlaybackController
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiMessenger
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.library.MainDispatcherRule
import com.spautifaille.ui.library.collectInBackground
import com.spautifaille.ui.library.track
import com.spautifaille.ui.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DownloadsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val downloads = MutableStateFlow<List<Download>>(emptyList())
    private val storage = MutableStateFlow(StorageUsage(0, 0, 0))
    private val repository = mockk<DownloadRepository>(relaxed = true) {
        every { observeDownloads() } returns downloads
        every { observeStorageUsage() } returns storage
    }
    private val playback = mockk<PlaybackController>(relaxed = true)
    private val messenger = UiMessenger()

    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val wifiOnly = MutableStateFlow(true)
    private val unmetered = MutableStateFlow(true)
    private val network = object : NetworkMonitor {
        override val isUnmetered: Flow<Boolean> = unmetered
    }

    init {
        every { settings.settings } answers { wifiOnly.map { AppSettings(downloadOverWifiOnly = it) } }
    }

    private fun viewModel() = DownloadsViewModel(repository, playback, messenger, settings, network)

    private fun download(index: Int, state: DownloadState, createdAt: Long = index.toLong(), error: String? = null) =
        Download(
            track = track(index),
            state = state,
            progress = if (state == DownloadState.COMPLETED) 1f else 0.5f,
            downloadedBytes = 5,
            totalBytes = 10,
            filePath = if (state == DownloadState.COMPLETED) "/f$index" else null,
            error = error,
            createdAt = createdAt,
        )

    @Test
    fun `initial state is loading`() {
        val state = viewModel().uiState.value
        assertTrue(state.isLoading)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `empty repository gives the empty state`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `downloads are split into active and completed sections`() = runTest {
        downloads.value = listOf(
            download(1, DownloadState.COMPLETED),
            download(2, DownloadState.FAILED, error = "Connexion impossible"),
            download(3, DownloadState.QUEUED),
            download(4, DownloadState.RUNNING),
            download(5, DownloadState.COMPLETED),
        )
        val vm = viewModel()
        collectInBackground(vm.uiState)

        val state = vm.uiState.value
        // RUNNING d'abord, puis la file, puis les échecs.
        assertEquals(listOf("video4", "video3", "video2"), state.active.map { it.track.id })
        assertEquals(1, state.failedCount)
        // Terminés : le plus récent en premier.
        assertEquals(listOf("video5", "video1"), state.completed.map { it.track.id })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `queued downloads keep their arrival order`() = runTest {
        downloads.value = listOf(
            download(1, DownloadState.QUEUED, createdAt = 30),
            download(2, DownloadState.QUEUED, createdAt = 10),
            download(3, DownloadState.PAUSED, createdAt = 20),
        )
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertEquals(listOf("video2", "video3", "video1"), vm.uiState.value.active.map { it.track.id })
    }

    @Test
    fun `storage usage is exposed and updates`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertEquals(StorageUsage(0, 0, 0), vm.uiState.value.storage)

        storage.value = StorageUsage(downloadsBytes = 1_000, cacheBytes = 2_000, downloadCount = 3)

        assertEquals(StorageUsage(1_000, 2_000, 3), vm.uiState.value.storage)
    }

    @Test
    fun `list updates flow into the state`() = runTest {
        val vm = viewModel()
        collectInBackground(vm.uiState)

        downloads.value = listOf(download(1, DownloadState.RUNNING))
        assertEquals(1, vm.uiState.value.active.size)

        downloads.value = listOf(download(1, DownloadState.COMPLETED))
        assertTrue(vm.uiState.value.active.isEmpty())
        assertEquals(1, vm.uiState.value.completed.size)
    }

    @Test
    fun `play starts the completed queue from the tapped index`() = runTest {
        downloads.value = listOf(
            download(1, DownloadState.COMPLETED, createdAt = 1),
            download(2, DownloadState.RUNNING),
            download(3, DownloadState.COMPLETED, createdAt = 3),
            download(4, DownloadState.COMPLETED, createdAt = 4),
        )
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.play(1)

        // Ordre affiché : 4, 3, 1 -> l'index 1 est le titre 3 ; seuls les titres terminés sont mis en file.
        verify { playback.play(listOf(track(4), track(3), track(1)), 1, false) }
    }

    @Test
    fun `play ignores an out of range index`() = runTest {
        downloads.value = listOf(download(1, DownloadState.COMPLETED))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.play(5)
        vm.play(-1)

        verify(exactly = 0) { playback.play(any(), any(), any()) }
    }

    @Test
    fun `cancel retry and delete are forwarded to the repository`() = runTest {
        val vm = viewModel()

        vm.cancel("a")
        vm.retry("b")
        vm.delete("c")
        vm.deleteAll()

        coVerify { repository.cancel("a") }
        coVerify { repository.retry("b") }
        coVerify { repository.delete("c") }
        coVerify { repository.deleteAll() }
    }

    @Test
    fun `retry all only retries failed downloads`() = runTest {
        downloads.value = listOf(
            download(1, DownloadState.FAILED),
            download(2, DownloadState.QUEUED),
            download(3, DownloadState.FAILED),
            download(4, DownloadState.COMPLETED),
        )
        val vm = viewModel()
        collectInBackground(vm.uiState)

        vm.retryAllFailed()

        coVerify(exactly = 1) { repository.retry("video1") }
        coVerify(exactly = 1) { repository.retry("video3") }
        coVerify(exactly = 0) { repository.retry("video2") }
        coVerify(exactly = 0) { repository.retry("video4") }
    }

    @Test
    fun `successful deletes show a confirmation message`() = runTest {
        val vm = viewModel()
        messenger.messages.test {
            vm.delete("a")
            assertEquals(UiText.of(R.string.snack_download_deleted), awaitItem())
            vm.deleteAll()
            assertEquals(UiText.of(R.string.dl_all_deleted), awaitItem())
        }
    }

    // region Attente du Wi-Fi

    @Test
    fun `queued downloads on a metered network with wifi only show the waiting banner`() = runTest {
        downloads.value = listOf(download(1, DownloadState.QUEUED))
        unmetered.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)

        assertTrue(vm.uiState.value.waitingForWifi)
    }

    @Test
    fun `no banner on wifi, without the wifi only setting, or without queued downloads`() = runTest {
        downloads.value = listOf(download(1, DownloadState.QUEUED))
        val vm = viewModel()
        collectInBackground(vm.uiState)

        // Réseau non facturé : les téléchargements peuvent avancer.
        assertFalse(vm.uiState.value.waitingForWifi)

        // Réseau mobile mais réglage désactivé.
        unmetered.value = false
        wifiOnly.value = false
        assertFalse(vm.uiState.value.waitingForWifi)

        // Réglage actif, réseau mobile, mais plus rien en attente (en cours ou en échec seulement).
        wifiOnly.value = true
        assertTrue(vm.uiState.value.waitingForWifi)
        downloads.value = listOf(download(1, DownloadState.RUNNING), download(2, DownloadState.FAILED))
        assertFalse(vm.uiState.value.waitingForWifi)
    }

    @Test
    fun `the banner disappears when wifi comes back`() = runTest {
        downloads.value = listOf(download(1, DownloadState.QUEUED))
        unmetered.value = false
        val vm = viewModel()
        collectInBackground(vm.uiState)
        assertTrue(vm.uiState.value.waitingForWifi)

        unmetered.value = true

        assertFalse(vm.uiState.value.waitingForWifi)
    }

    @Test
    fun `allowing mobile data turns the wifi only setting off`() = runTest {
        val vm = viewModel()

        vm.allowMobileData()

        coVerify(exactly = 1) { settings.setDownloadOverWifiOnly(false) }
    }

    // endregion

    @Test
    fun `a failing repository call shows an error message instead of crashing`() = runTest {
        coEvery { repository.delete(any()) } throws IllegalStateException("boom")
        val vm = viewModel()
        messenger.messages.test {
            vm.delete("a")
            assertEquals(UiText.of(R.string.dl_action_failed), awaitItem())
        }
    }
}
