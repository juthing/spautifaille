package com.spautifaille.player.datasource

import app.cash.turbine.test
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.Download
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedOfflineAvailabilityTest {

    private val downloadList = MutableStateFlow<List<Download>>(emptyList())
    private val downloads = mockk<DownloadRepository> { every { observeDownloads() } returns downloadList }
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settings = mockk<SettingsRepository> { every { settings } returns settingsFlow }
    private var cached: Map<AudioQuality, Set<String>> = emptyMap()

    private fun download(id: String, state: DownloadState) =
        Download(Track(id, "T$id", "A"), state, 1f, 1, 1, "/f/$id", null, 0)

    @Test
    fun `merges completed downloads and cached tracks, and follows both`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val target = CachedOfflineAvailability(downloads, settings, { q -> cached[q].orEmpty() }, dispatcher, pollMs = 1_000)
        cached = mapOf(AudioQuality.BEST to setOf("c1"))
        downloadList.value = listOf(download("d1", DownloadState.COMPLETED), download("d2", DownloadState.RUNNING))

        target.observePlayableIds().test {
            assertEquals(setOf("c1", "d1"), awaitItem())

            // Un titre entre dans le cache de streaming : repéré au prochain relevé.
            cached = mapOf(AudioQuality.BEST to setOf("c1", "c2"))
            advanceTimeBy(1_100)
            assertEquals(setOf("c1", "c2", "d1"), awaitItem())

            // Un téléchargement se termine : immédiat.
            downloadList.value = listOf(download("d1", DownloadState.COMPLETED), download("d2", DownloadState.COMPLETED))
            assertEquals(setOf("c1", "c2", "d1", "d2"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing the audio quality rescans the cache for the new quality`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val target = CachedOfflineAvailability(downloads, settings, { q -> cached[q].orEmpty() }, dispatcher, pollMs = 1_000)
        cached = mapOf(AudioQuality.BEST to setOf("a"), AudioQuality.DATA_SAVER to setOf("b"))

        target.observePlayableIds().test {
            assertEquals(setOf("a"), awaitItem())
            settingsFlow.value = AppSettings(audioQuality = AudioQuality.DATA_SAVER)
            assertEquals(setOf("b"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
