package com.spautifaille.player

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlayerEvent
import com.spautifaille.player.sleep.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionContractTest {

    private val allErrors = listOf(
        AppError.Unavailable,
        AppError.AgeRestricted,
        AppError.GeoBlocked,
        AppError.PaidContent,
        AppError.BotDetected,
        AppError.Network,
        AppError.StreamExpired,
        AppError.NoAudioStream,
        AppError.ExtractionBroken("player js changed: a:b"),
        AppError.ExtractionBroken(null),
        AppError.ExtractionBroken(""),
        AppError.Unknown("boom"),
        AppError.Unknown(null),
    )

    @Test
    fun `AppError codec round trip`() {
        allErrors.forEach { error ->
            assertEquals(error, AppErrorCodec.decode(AppErrorCodec.encode(error)))
        }
    }

    @Test
    fun `AppError codec tolerates missing and unknown codes`() {
        assertEquals(AppError.Unknown(null), AppErrorCodec.decode(null))
        assertEquals(AppError.Unknown("future_code"), AppErrorCodec.decode("future_code"))
    }

    @Test
    fun `session extras round trip`() {
        val states = listOf(
            SessionContract.PublishedState(),
            SessionContract.PublishedState(liked = true, offline = true, sleepTimer = SleepTimerState.EndOfTrack),
            SessionContract.PublishedState(liked = true, sleepTimer = SleepTimerState.At(123_456L)),
            SessionContract.PublishedState(hasHistory = true),
        )
        states.forEach { state ->
            assertEquals(state, SessionContract.decodeExtras(SessionContract.encodeExtras(state)))
        }
        assertEquals(SessionContract.PublishedState(), SessionContract.decodeExtras(null))
    }

    @Test
    fun `events round trip`() {
        val track = Track(id = "vid", title = "Titre", artist = "Artiste")
        val events = listOf(
            PlayerEvent.TrackSkipped(track, AppError.Unavailable),
            PlayerEvent.Error(track, AppError.ExtractionBroken("x")),
            PlayerEvent.Error(null, AppError.Network),
            PlayerEvent.SleepTimerFinished,
        )
        events.forEach { event ->
            assertEquals(event, SessionContract.decodeEvent(SessionContract.encodeEvent(event)))
        }
    }

    @Test
    fun `unknown event bundle decodes to null`() {
        assertNull(SessionContract.decodeEvent(android.os.Bundle()))
    }

    @Test
    fun `play next arguments round trip`() {
        val tracks = listOf(
            Track("a", "A", "Art", artistUrl = "https://y/c/1", album = "Alb", durationMs = 12_000, thumbnailUrl = "https://i/1.jpg"),
            Track("b", "B", "Art"),
        )
        assertEquals(tracks, SessionContract.decodePlayNextArgs(SessionContract.playNextArgs(tracks)))
        assertEquals(emptyList<Track>(), SessionContract.decodePlayNextArgs(android.os.Bundle()))
    }

    @Test
    fun `sleep timer arguments`() {
        assertEquals(1_800_000L, SessionContract.sleepTimerArgs(1_800_000L).getLong(SessionContract.KEY_DURATION_MS))
        assertEquals(true, SessionContract.sleepTimerEndOfTrackArgs().getBoolean(SessionContract.KEY_END_OF_TRACK))
    }
}
