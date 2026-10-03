package com.spautifaille.player.volume

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.spautifaille.domain.loudness.LoudnessNormalizer
import com.spautifaille.domain.loudness.PlaybackGain
import com.spautifaille.domain.model.Track
import com.spautifaille.player.MediaItemMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LoudnessGainControllerTest {

    private class RecordingOutput : VolumeOutput {
        var level = 1f
        var boost = 0
        override fun setVolume(volume: Float) {
            level = volume
        }
        override fun setBoostMillibels(millibels: Int) {
            boost = millibels
        }
    }

    private lateinit var player: ExoPlayer
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val output = RecordingOutput()
    private val volume = PlaybackVolume(output)

    /** Niveaux connus en mémoire (« peek »). */
    private val known = mutableMapOf("loud" to 6f, "quiet" to -3f, "mid" to 0.1f)
    private val lookups = mutableListOf<String>()
    private var lookup: suspend (String) -> Float? = { null }

    private var now = 1_000L

    private fun controller(timeoutMs: Long = 5_000L) = LoudnessGainController(
        player = player,
        scope = scope,
        volume = volume,
        peek = known::get,
        lookup = { id ->
            lookups += id
            lookup(id)
        },
        lookupTimeoutMs = timeoutMs,
        clock = { now },
    )

    private fun items(vararg ids: String): List<MediaItem> =
        MediaItemMapper.toMediaItems(ids.map { Track(it, "Titre $it", "A") })

    @Before
    fun setUp() {
        player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun `le gain suit le titre courant et se corrige a chaque saut`() {
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaItems(items("loud", "quiet", "mid"))
        controller.start()

        assertEquals(LoudnessNormalizer.gainFor(6f).attenuation, output.level, 1e-6f)
        assertEquals(0, output.boost)

        player.seekToNextMediaItem() // quiet : +3 dB
        assertEquals(1f, output.level, 0f)
        assertEquals(300, output.boost)

        player.seekToNextMediaItem() // mid : écart imperceptible
        assertEquals(1f, output.level, 0f)
        assertEquals(0, output.boost)

        player.seekToPreviousMediaItem()
        player.seekToPreviousMediaItem() // retour sur loud
        assertEquals(LoudnessNormalizer.gainFor(6f).attenuation, output.level, 1e-6f)
        assertEquals(0, output.boost)
        controller.release()
    }

    @Test
    fun `enchainement automatique sans coupure change le gain a la frontiere`() {
        val itemList = items("loud", "quiet")
        val timeline = FakeTimeline(
            *itemList.map {
                TimelineWindowDefinition.Builder().setUid(it.mediaId).setDurationUs(1_000_000).setMediaItem(it).build()
            }.toTypedArray(),
        )
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaSource(FakeMediaSource(timeline))
        player.prepare()
        controller.start()
        assertEquals("loud", player.currentMediaItem?.mediaId)
        assertEquals(LoudnessNormalizer.gainFor(6f).attenuation, output.level, 1e-6f)

        player.play()
        TestPlayerRunHelper.runUntilPositionDiscontinuity(player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

        assertEquals("quiet", player.currentMediaItem?.mediaId)
        assertEquals(1f, output.level, 0f)
        assertEquals(300, output.boost)
        controller.release()
    }

    @Test
    fun `desactivation et reactivation a chaud`() {
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaItems(items("loud"))
        controller.start()
        assertTrue(output.level < 1f)

        controller.setEnabled(false)
        assertEquals(1f, output.level, 0f)
        assertEquals(0, output.boost)

        controller.setEnabled(true)
        assertEquals(LoudnessNormalizer.gainFor(6f).attenuation, output.level, 1e-6f)
        controller.release()
    }

    @Test
    fun `neutre tant que le reglage n a pas ete lu`() {
        val controller = controller() // activé par la collecte des réglages, pas avant
        player.setMediaItems(items("loud"))
        controller.start()
        assertEquals(1f, output.level, 0f)
        controller.release()
    }

    @Test
    fun `niveau inconnu puis trouve plus tard est applique au titre toujours courant`() {
        val deferred = CompletableDeferred<Float?>()
        lookup = { deferred.await() }
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaItems(items("loin"))
        controller.start()
        assertEquals(1f, output.level, 0f) // neutre en attendant
        assertEquals(listOf("loin"), lookups)

        deferred.complete(8f)
        assertEquals(LoudnessNormalizer.gainFor(8f).attenuation, output.level, 1e-6f)
        controller.release()
    }

    @Test
    fun `niveau trouve apres un changement de titre est ignore`() {
        val deferred = CompletableDeferred<Float?>()
        lookup = { deferred.await() }
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaItems(items("loin", "quiet"))
        controller.start()

        player.seekToNextMediaItem() // quiet (connu) : +3 dB
        deferred.complete(8f) // résultat tardif de « loin » : ne doit rien changer
        assertEquals(1f, output.level, 0f)
        assertEquals(300, output.boost)
        controller.release()
    }

    @Test
    fun `echec de recherche reste neutre et n est pas reessaye`() {
        lookup = { throw java.io.IOException("hors ligne") }
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaItems(items("loin", "quiet"))
        controller.start()
        assertEquals(1f, output.level, 0f)

        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()
        assertEquals(1f, output.level, 0f)
        assertEquals(listOf("loin"), lookups)
        controller.release()
    }

    @Test
    fun `titre en echec est redemande une fois le delai de reessai ecoule`() {
        lookup = { throw java.io.IOException("hors ligne") }
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaItems(items("loin", "quiet"))
        controller.start()
        assertEquals(listOf("loin"), lookups)

        // Le réseau est revenu : après le délai, le même titre est de nouveau cherché et son gain appliqué.
        lookup = { 8f }
        now += LoudnessGainController.RETRY_AFTER_MS + 1
        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()

        assertEquals(listOf("loin", "loin"), lookups)
        assertEquals(LoudnessNormalizer.gainFor(8f).attenuation, output.level, 1e-6f)
        controller.release()
    }

    @Test
    fun `delai de recherche depasse reste neutre`() {
        lookup = { delay(60_000); 5f }
        val controller = controller(timeoutMs = 1_000).also { it.setEnabled(true) }
        player.setMediaItems(items("loin"))
        controller.start()

        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertEquals(1f, output.level, 0f)
        assertEquals(0, output.boost)
        controller.release()
    }

    @Test
    fun `liberation remet le gain neutre`() {
        val controller = controller().also { it.setEnabled(true) }
        player.setMediaItems(items("loud"))
        controller.start()
        controller.release()
        assertEquals(PlaybackGain.Neutral, volume.gain)
        assertEquals(1f, output.level, 0f)
    }
}
