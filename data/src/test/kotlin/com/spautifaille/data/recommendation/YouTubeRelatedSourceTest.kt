package com.spautifaille.data.recommendation

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YouTubeRelatedSourceTest {

    private val streams = mockk<StreamRepository>()
    private val source = YouTubeRelatedSource(streams)
    private val seed = Track("seed", "Seed", "Artiste")

    private fun tracks(prefix: String, count: Int) = (1..count).map { Track("$prefix$it", "T $prefix$it", "A") }

    @Test
    fun `uses related only when it is large enough`() = runTest {
        coEvery { streams.related("seed") } returns tracks("r", 20)

        val out = source.similar(seed, 25)

        assertEquals(20, out.size)
        coVerify(exactly = 0) { streams.mix(any()) }
        assertEquals("youtube_related", source.id)
    }

    @Test
    fun `completes with the mix when related returns fewer than 10 tracks`() = runTest {
        coEvery { streams.related("seed") } returns tracks("r", 4)
        coEvery { streams.mix("seed") } returns tracks("r", 2) + tracks("m", 10)

        val out = source.similar(seed, 25)

        assertEquals(4 + 10, out.size)
        assertEquals(out.map { it.id }.distinct(), out.map { it.id })
        assertEquals(listOf("r1", "r2", "r3", "r4"), out.take(4).map { it.id })
    }

    @Test
    fun `never returns the seed and honours the limit`() = runTest {
        coEvery { streams.related("seed") } returns listOf(seed) + tracks("r", 30)

        val out = source.similar(seed, 12)

        assertEquals(12, out.size)
        assertTrue(out.none { it.id == "seed" })
    }

    @Test
    fun `falls back to the mix when related fails`() = runTest {
        coEvery { streams.related("seed") } throws AppException(AppError.Unavailable)
        coEvery { streams.mix("seed") } returns tracks("m", 5)

        assertEquals(5, source.similar(seed, 25).size)
    }

    @Test
    fun `rethrows the related error when the mix is empty too`() = runTest {
        coEvery { streams.related("seed") } throws AppException(AppError.Network)
        coEvery { streams.mix("seed") } returns emptyList()

        try {
            source.similar(seed, 25)
            fail("AppException attendue")
        } catch (e: AppException) {
            assertEquals(AppError.Network, e.error)
        }
    }
}
