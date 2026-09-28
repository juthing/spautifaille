package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.TrackCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewPipeStreamRepositoryTest {

    private val cache = mockk<TrackCache>(relaxed = true)
    private val initializer = mockk<NewPipeInitializer>(relaxed = true)

    private fun repo(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        NewPipeStreamRepository(initializer, cache, dispatcher)

    @Test fun trackIsAnsweredFromCacheWithoutInitializingNewPipe() = runTest {
        val cached = Track(id = "dQw4w9WgXcQ", title = "t", artist = "a")
        coEvery { cache.get("dQw4w9WgXcQ") } returns cached
        val result = repo(UnconfinedTestDispatcher(testScheduler)).track("dQw4w9WgXcQ")
        assertEquals(cached, result)
        coVerify(exactly = 0) { cache.put(any()) }
        io.mockk.verify(exactly = 0) { initializer.ensureInitialized() }
    }

    @Test fun blankSuggestionQueryReturnsEmpty() = runTest {
        assertEquals(emptyList<String>(), repo(UnconfinedTestDispatcher(testScheduler)).suggestions("  "))
    }

    @Test fun playlistUrlDetection() {
        val repo = repo(kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(repo.isPlaylistUrl("https://www.youtube.com/playlist?list=PLrEnWoR732-BHrPp_Pm8_VleD68f9s14-"))
        assertTrue(repo.isPlaylistUrl("https://music.youtube.com/playlist?list=PLrEnWoR732-BHrPp_Pm8_VleD68f9s14-"))
        assertFalse(repo.isPlaylistUrl("https://example.com/"))
        assertFalse(repo.isPlaylistUrl("not a url"))
    }
}
