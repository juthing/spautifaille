package com.spautifaille.player

import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.media3.session.SessionCommand
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.PlayerEvent
import com.spautifaille.player.sleep.SleepTimerState

/**
 * Contrat partagé entre [PlaybackService] et [PlaybackControllerImpl] : noms des commandes personnalisées,
 * clés des extras de session (état publié par le service) et encodage des événements.
 */
object SessionContract {
    private const val PREFIX = "com.spautifaille.player."

    // --- Commandes personnalisées (contrôleur -> service) ---
    const val ACTION_TOGGLE_LIKE = PREFIX + "TOGGLE_LIKE"
    const val ACTION_SET_SLEEP_TIMER = PREFIX + "SET_SLEEP_TIMER"
    const val ACTION_CANCEL_SLEEP_TIMER = PREFIX + "CANCEL_SLEEP_TIMER"

    /** « Lire ensuite » : insère les titres passés en argument juste après le titre courant, même en mode aléatoire. */
    const val ACTION_PLAY_NEXT = PREFIX + "PLAY_NEXT"

    // Arguments de ACTION_PLAY_NEXT
    const val KEY_TRACKS = "tracks"

    /** Commande diffusée du service vers les contrôleurs pour signaler un événement ([encodeEvent]). */
    const val ACTION_EVENT = PREFIX + "EVENT"

    // Arguments de ACTION_SET_SLEEP_TIMER
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_END_OF_TRACK = "end_of_track"

    // --- Extras de session (service -> contrôleurs) ---
    const val EXTRA_LIKED = PREFIX + "extra.LIKED"
    const val EXTRA_OFFLINE = PREFIX + "extra.OFFLINE"
    const val EXTRA_HAS_HISTORY = PREFIX + "extra.HAS_HISTORY"
    const val EXTRA_SLEEP_MODE = PREFIX + "extra.SLEEP_MODE"
    const val EXTRA_SLEEP_ENDS_AT = PREFIX + "extra.SLEEP_ENDS_AT"

    const val SLEEP_MODE_OFF = "off"
    const val SLEEP_MODE_AT = "at"
    const val SLEEP_MODE_END_OF_TRACK = "end_of_track"

    // --- Événements ---
    const val KEY_EVENT_TYPE = "event_type"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_MEDIA_ID = "media_id"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_ARTIST_URL = "artist_url"
    const val KEY_ALBUM = "album"
    const val KEY_THUMBNAIL_URL = "thumbnail_url"

    const val EVENT_TRACK_SKIPPED = "track_skipped"
    const val EVENT_ERROR = "error"
    const val EVENT_SLEEP_TIMER_FINISHED = "sleep_timer_finished"

    val toggleLikeCommand: SessionCommand get() = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    val setSleepTimerCommand: SessionCommand get() = SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY)
    val cancelSleepTimerCommand: SessionCommand get() = SessionCommand(ACTION_CANCEL_SLEEP_TIMER, Bundle.EMPTY)
    val eventCommand: SessionCommand get() = SessionCommand(ACTION_EVENT, Bundle.EMPTY)
    val playNextCommand: SessionCommand get() = SessionCommand(ACTION_PLAY_NEXT, Bundle.EMPTY)

    fun playNextArgs(tracks: List<Track>): Bundle = Bundle().apply {
        putParcelableArrayList(KEY_TRACKS, ArrayList(tracks.map(::trackToBundle)))
    }

    fun decodePlayNextArgs(args: Bundle): List<Track> =
        BundleCompat.getParcelableArrayList(args, KEY_TRACKS, Bundle::class.java).orEmpty().mapNotNull(::bundleToTrack)

    private fun trackToBundle(track: Track): Bundle = Bundle().apply {
        putString(KEY_MEDIA_ID, track.id)
        putString(KEY_TITLE, track.title)
        putString(KEY_ARTIST, track.artist)
        track.artistUrl?.let { putString(KEY_ARTIST_URL, it) }
        track.album?.let { putString(KEY_ALBUM, it) }
        track.durationMs?.let { putLong(KEY_DURATION_MS, it) }
        track.thumbnailUrl?.let { putString(KEY_THUMBNAIL_URL, it) }
    }

    private fun bundleToTrack(bundle: Bundle): Track? {
        val id = bundle.getString(KEY_MEDIA_ID) ?: return null
        return Track(
            id = id,
            title = bundle.getString(KEY_TITLE).orEmpty(),
            artist = bundle.getString(KEY_ARTIST).orEmpty(),
            artistUrl = bundle.getString(KEY_ARTIST_URL),
            album = bundle.getString(KEY_ALBUM),
            durationMs = if (bundle.containsKey(KEY_DURATION_MS)) bundle.getLong(KEY_DURATION_MS) else null,
            thumbnailUrl = bundle.getString(KEY_THUMBNAIL_URL),
        )
    }

    fun sleepTimerArgs(durationMs: Long): Bundle = Bundle().apply { putLong(KEY_DURATION_MS, durationMs) }
    fun sleepTimerEndOfTrackArgs(): Bundle = Bundle().apply { putBoolean(KEY_END_OF_TRACK, true) }

    // --- Extras de session ---

    data class PublishedState(
        val liked: Boolean = false,
        val offline: Boolean = false,
        val sleepTimer: SleepTimerState = SleepTimerState.Off,
        /** Un titre déjà écouté (hors file) est disponible pour « précédent ». */
        val hasHistory: Boolean = false,
    )

    fun encodeExtras(state: PublishedState): Bundle = Bundle().apply {
        putBoolean(EXTRA_LIKED, state.liked)
        putBoolean(EXTRA_OFFLINE, state.offline)
        putBoolean(EXTRA_HAS_HISTORY, state.hasHistory)
        when (val timer = state.sleepTimer) {
            SleepTimerState.Off -> putString(EXTRA_SLEEP_MODE, SLEEP_MODE_OFF)
            SleepTimerState.EndOfTrack -> putString(EXTRA_SLEEP_MODE, SLEEP_MODE_END_OF_TRACK)
            is SleepTimerState.At -> {
                putString(EXTRA_SLEEP_MODE, SLEEP_MODE_AT)
                putLong(EXTRA_SLEEP_ENDS_AT, timer.endsAtElapsedMs)
            }
        }
    }

    fun decodeExtras(extras: Bundle?): PublishedState {
        if (extras == null) return PublishedState()
        val timer = when (extras.getString(EXTRA_SLEEP_MODE)) {
            SLEEP_MODE_AT -> SleepTimerState.At(extras.getLong(EXTRA_SLEEP_ENDS_AT))
            SLEEP_MODE_END_OF_TRACK -> SleepTimerState.EndOfTrack
            else -> SleepTimerState.Off
        }
        return PublishedState(
            liked = extras.getBoolean(EXTRA_LIKED),
            offline = extras.getBoolean(EXTRA_OFFLINE),
            sleepTimer = timer,
            hasHistory = extras.getBoolean(EXTRA_HAS_HISTORY),
        )
    }

    // --- Événements ---

    fun encodeEvent(event: PlayerEvent): Bundle = Bundle().apply {
        when (event) {
            is PlayerEvent.TrackSkipped -> {
                putString(KEY_EVENT_TYPE, EVENT_TRACK_SKIPPED)
                putString(KEY_ERROR_CODE, AppErrorCodec.encode(event.error))
                putTrack(this, event.track)
            }
            is PlayerEvent.Error -> {
                putString(KEY_EVENT_TYPE, EVENT_ERROR)
                putString(KEY_ERROR_CODE, AppErrorCodec.encode(event.error))
                event.track?.let { putTrack(this, it) }
            }
            PlayerEvent.SleepTimerFinished -> putString(KEY_EVENT_TYPE, EVENT_SLEEP_TIMER_FINISHED)
        }
    }

    fun decodeEvent(args: Bundle): PlayerEvent? {
        val error = AppErrorCodec.decode(args.getString(KEY_ERROR_CODE))
        return when (args.getString(KEY_EVENT_TYPE)) {
            EVENT_TRACK_SKIPPED -> getTrack(args)?.let { PlayerEvent.TrackSkipped(it, error) }
            EVENT_ERROR -> PlayerEvent.Error(getTrack(args), error)
            EVENT_SLEEP_TIMER_FINISHED -> PlayerEvent.SleepTimerFinished
            else -> null
        }
    }

    private fun putTrack(bundle: Bundle, track: Track) {
        bundle.putString(KEY_MEDIA_ID, track.id)
        bundle.putString(KEY_TITLE, track.title)
        bundle.putString(KEY_ARTIST, track.artist)
    }

    private fun getTrack(args: Bundle): Track? {
        val id = args.getString(KEY_MEDIA_ID) ?: return null
        return Track(id = id, title = args.getString(KEY_TITLE).orEmpty(), artist = args.getString(KEY_ARTIST).orEmpty())
    }
}

/** Encodage texte de [AppError] (`code` ou `code:detail`) pour le transport dans les Bundles. */
object AppErrorCodec {
    private const val UNAVAILABLE = "unavailable"
    private const val AGE_RESTRICTED = "age_restricted"
    private const val GEO_BLOCKED = "geo_blocked"
    private const val PAID_CONTENT = "paid_content"
    private const val BOT_DETECTED = "bot_detected"
    private const val NETWORK = "network"
    private const val STREAM_EXPIRED = "stream_expired"
    private const val NO_AUDIO_STREAM = "no_audio_stream"
    private const val EXTRACTION_BROKEN = "extraction_broken"
    private const val UNKNOWN = "unknown"

    fun encode(error: AppError): String = when (error) {
        AppError.Unavailable -> UNAVAILABLE
        AppError.AgeRestricted -> AGE_RESTRICTED
        AppError.GeoBlocked -> GEO_BLOCKED
        AppError.PaidContent -> PAID_CONTENT
        AppError.BotDetected -> BOT_DETECTED
        AppError.Network -> NETWORK
        AppError.StreamExpired -> STREAM_EXPIRED
        AppError.NoAudioStream -> NO_AUDIO_STREAM
        is AppError.ExtractionBroken -> withDetail(EXTRACTION_BROKEN, error.detail)
        is AppError.Unknown -> withDetail(UNKNOWN, error.detail)
        // Erreurs de reconnaissance musicale : ne traversent jamais la session média.
        AppError.MicrophoneUnavailable, AppError.RecognitionUnavailable -> UNKNOWN
    }

    fun decode(code: String?): AppError {
        if (code == null) return AppError.Unknown(null)
        val separator = code.indexOf(':')
        val name = if (separator < 0) code else code.substring(0, separator)
        val detail = if (separator < 0) null else code.substring(separator + 1)
        return when (name) {
            UNAVAILABLE -> AppError.Unavailable
            AGE_RESTRICTED -> AppError.AgeRestricted
            GEO_BLOCKED -> AppError.GeoBlocked
            PAID_CONTENT -> AppError.PaidContent
            BOT_DETECTED -> AppError.BotDetected
            NETWORK -> AppError.Network
            STREAM_EXPIRED -> AppError.StreamExpired
            NO_AUDIO_STREAM -> AppError.NoAudioStream
            EXTRACTION_BROKEN -> AppError.ExtractionBroken(detail)
            UNKNOWN -> AppError.Unknown(detail)
            else -> AppError.Unknown(code)
        }
    }

    private fun withDetail(name: String, detail: String?) = if (detail == null) name else "$name:$detail"
}
