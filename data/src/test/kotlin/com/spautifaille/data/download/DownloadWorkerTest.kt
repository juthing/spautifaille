package com.spautifaille.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.spautifaille.data.local.DownloadEntity
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.toEntity
import com.spautifaille.data.local.track
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.AppSettings
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.model.ResolvedStream
import com.spautifaille.domain.model.ThemeMode
import com.spautifaille.domain.repository.SettingsRepository
import com.spautifaille.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class DownloadWorkerTest {

    private class FakeSettings : SettingsRepository {
        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun current() = AppSettings()
        override suspend fun setAudioQuality(quality: AudioQuality) = Unit
        override suspend fun setDownloadOverWifiOnly(enabled: Boolean) = Unit
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setStreamCacheSizeMb(sizeMb: Int) = Unit
        override suspend fun setLastFmApiKey(key: String?) = Unit
    }

    private lateinit var context: Context
    private lateinit var db: SpautifailleDatabase
    private lateinit var server: MockWebServer
    private lateinit var streams: StreamRepository
    private val served = mutableListOf<String>() // "path?range"

    private val data = ByteArray(200_000) { (it * 7 % 253).toByte() }
    private val downloadsDir get() = File(context.filesDir, "downloads")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = createInMemoryDatabase(context)
        downloadsDir.deleteRecursively()
        streams = mockk()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.url.queryParameter("range")!!
                served += "${request.url.encodedPath}?$range"
                if (request.url.encodedPath == "/expired") return MockResponse.Builder().code(403).build()
                if (request.url.encodedPath == "/broken") return MockResponse.Builder().code(500).build()
                val (start, end) = range.split('-').map { it.toInt() }
                return MockResponse.Builder().code(206)
                    .body(Buffer().write(data.copyOfRange(start, minOf(end + 1, data.size)))).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun stream(
        path: String = "/audio",
        mime: String? = "audio/mp4",
        length: Long? = data.size.toLong(),
        dash: String? = null,
    ) = ResolvedStream(
        videoId = "vid",
        url = server.url(path).toString(),
        mimeType = mime,
        codec = null,
        bitrate = 128_000,
        contentLength = length,
        headers = mapOf("User-Agent" to "VisionOS-UA"),
        expiresAtMs = Long.MAX_VALUE,
        dashManifest = dash,
    )

    private suspend fun seed(id: String = "vid", state: DownloadState = DownloadState.QUEUED, downloaded: Long = 0) {
        db.trackDao().upsertAll(listOf(track(id).toEntity(updatedAt = 1)))
        db.downloadDao().upsert(DownloadEntity(id, state.name, downloaded, null, null, null, null, 10, 10))
    }

    private fun worker(id: String = "vid", attempt: Int = 0, limiter: DownloadLimiter = DownloadLimiter()): DownloadWorker =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWork.KEY_TRACK_ID to id))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        DownloadWorker(
                            appContext, workerParameters, db.downloadDao(), db.trackDao(), streams, FakeSettings(),
                            OkHttpClient(), limiter, Dispatchers.IO,
                        )
                },
            )
            .build()

    private fun run(worker: DownloadWorker): Result = runBlocking { worker.doWork() }

    private fun row(id: String = "vid") = runBlocking { db.downloadDao().get(id) }

    @Test
    fun downloadsTheStreamAndMarksTheRowCompleted() = runBlocking {
        seed()
        coEvery { streams.resolveAudio("vid", AudioQuality.BEST) } returns stream()

        val result = run(worker())

        assertEquals(Result.success(), result)
        val file = File(downloadsDir, "vid.m4a")
        assertArrayEquals(data, file.readBytes())
        assertFalse(File(downloadsDir, "vid.m4a.part").exists())
        val row = row()!!
        assertEquals(DownloadState.COMPLETED.name, row.state)
        assertEquals(file.absolutePath, row.filePath)
        assertEquals("audio/mp4", row.mimeType)
        assertEquals(data.size.toLong(), row.downloadedBytes)
        assertEquals(data.size.toLong(), row.totalBytes)
        assertNull(row.error)
        // Blocs de ~1,5 Mo : un seul suffit pour 200 Ko.
        assertEquals(listOf("/audio?0-${data.size - 1}"), served)
    }

    @Test
    fun webmMimeTypeGivesAWebmExtension() = runBlocking {
        seed()
        coEvery { streams.resolveAudio(any(), any()) } returns stream(mime = "audio/webm")

        assertEquals(Result.success(), run(worker()))

        assertTrue(File(downloadsDir, "vid.webm").exists())
        assertEquals("audio/webm", row()!!.mimeType)
    }

    @Test
    fun resumesFromTheExistingPartFile() = runBlocking {
        seed(downloaded = 50_000)
        downloadsDir.mkdirs()
        File(downloadsDir, "vid.m4a.part").writeBytes(data.copyOfRange(0, 50_000))
        coEvery { streams.resolveAudio(any(), any()) } returns stream()

        assertEquals(Result.success(), run(worker()))

        assertArrayEquals(data, File(downloadsDir, "vid.m4a").readBytes())
        assertEquals(listOf("/audio?50000-${data.size - 1}"), served)
    }

    @Test
    fun staleForeignPartFileIsDiscarded() = runBlocking {
        seed()
        downloadsDir.mkdirs()
        val foreign = File(downloadsDir, "vid.webm.part").apply { writeBytes(ByteArray(1000)) }
        coEvery { streams.resolveAudio(any(), any()) } returns stream(mime = "audio/mp4")

        assertEquals(Result.success(), run(worker()))

        assertFalse(foreign.exists())
        assertArrayEquals(data, File(downloadsDir, "vid.m4a").readBytes())
    }

    @Test
    fun forbiddenUrlIsResolvedAgainOnceAndTheDownloadContinues() = runBlocking {
        seed()
        coEvery { streams.resolveAudio("vid", any()) } returnsMany listOf(stream("/expired"), stream("/fresh"))

        assertEquals(Result.success(), run(worker()))

        assertArrayEquals(data, File(downloadsDir, "vid.m4a").readBytes())
        coVerify(exactly = 2) { streams.resolveAudio("vid", any()) }
        assertEquals(listOf("/expired?0-${data.size - 1}", "/fresh?0-${data.size - 1}"), served)
    }

    @Test
    fun dashOnlyStreamFailsWithAFrenchMessage() = runBlocking {
        seed()
        coEvery { streams.resolveAudio(any(), any()) } returns stream(dash = "<MPD/>")

        assertEquals(Result.failure(), run(worker()))

        val row = row()!!
        assertEquals(DownloadState.FAILED.name, row.state)
        assertEquals("Format non téléchargeable", row.error)
        assertTrue(served.isEmpty())
    }

    @Test
    fun unrecoverableErrorsFailTheRowAndDropThePartFile() = runBlocking {
        val cases = mapOf(
            AppError.Unavailable to "Contenu indisponible",
            AppError.AgeRestricted to "Contenu soumis à une restriction d'âge",
            AppError.GeoBlocked to "Indisponible dans ton pays",
            AppError.PaidContent to "Contenu payant ou réservé aux abonnés",
            AppError.NoAudioStream to "Aucun flux audio disponible",
        )
        for ((error, message) in cases) {
            val id = "id-${error}"
            seed(id)
            downloadsDir.mkdirs()
            val part = File(downloadsDir, "$id.m4a.part").apply { writeBytes(ByteArray(10)) }
            coEvery { streams.resolveAudio(id, any()) } throws AppException(error)

            assertEquals("$error", Result.failure(), run(worker(id)))

            assertEquals(DownloadState.FAILED.name, row(id)!!.state)
            assertEquals(message, row(id)!!.error)
            assertFalse(part.exists())
        }
    }

    @Test
    fun networkAndBotErrorsRetryAndKeepThePartFile() = runBlocking {
        for (error in listOf(AppError.Network, AppError.BotDetected)) {
            val id = "id-$error"
            seed(id, DownloadState.RUNNING, downloaded = 10)
            downloadsDir.mkdirs()
            val part = File(downloadsDir, "$id.m4a.part").apply { writeBytes(ByteArray(10)) }
            coEvery { streams.resolveAudio(id, any()) } throws AppException(error)

            assertEquals(Result.retry(), run(worker(id)))

            assertEquals(DownloadState.QUEUED.name, row(id)!!.state)
            assertTrue(part.exists())
        }
    }

    @Test
    fun serverErrorRetriesThenFailsAfterTheLastAttempt() = runBlocking {
        seed(downloaded = 0)
        coEvery { streams.resolveAudio(any(), any()) } returns stream("/broken")

        assertEquals(Result.retry(), run(worker(attempt = 0)))
        assertEquals(DownloadState.QUEUED.name, row()!!.state)

        assertEquals(Result.failure(), run(worker(attempt = DownloadWorker.MAX_ATTEMPTS - 1)))
        assertEquals(DownloadState.FAILED.name, row()!!.state)
        assertEquals("Connexion impossible", row()!!.error)
    }

    @Test
    fun missingRowMeansTheDownloadWasCancelled() = runBlocking {
        val result = run(worker("ghost"))

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { streams.resolveAudio(any(), any()) }
        assertNull(row("ghost"))
    }

    @Test
    fun limiterNeverRunsMoreThanTheAllowedNumberOfBlocksAtOnce() = runBlocking {
        val limiter = DownloadLimiter(2)
        val running = AtomicInteger()
        var peak = 0

        (1..6).map {
            async(Dispatchers.Default) {
                limiter.withPermit {
                    val now = running.incrementAndGet()
                    synchronized(this@DownloadWorkerTest) { peak = maxOf(peak, now) }
                    delay(30)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(2, peak)
    }
}
