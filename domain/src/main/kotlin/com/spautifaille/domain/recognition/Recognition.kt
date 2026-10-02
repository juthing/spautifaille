package com.spautifaille.domain.recognition

import kotlinx.coroutines.flow.Flow

/** Titre identifié par le service de reconnaissance (métadonnées du catalogue, pas un titre YouTube). */
data class RecognizedTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val releaseYear: String? = null,
    val genre: String? = null,
) {
    /** Requête de recherche « titre artiste » pour retrouver le morceau sur YouTube Music. */
    val searchQuery: String get() = "$title $artist".trim()
}

/**
 * Moteur de reconnaissance : identifie un extrait audio.
 * Lève `AppException(AppError.Network)` hors ligne, `AppException(AppError.RecognitionUnavailable)` si le service refuse
 * ou limite les requêtes. Renvoie `null` quand aucun titre ne correspond.
 */
interface MusicRecognizer {
    /** [pcm] : PCM 16 bits mono à [sampleRate] Hz (le moteur n'accepte que [AudioCapture.SAMPLE_RATE]). */
    suspend fun recognize(pcm: ShortArray, sampleRate: Int): RecognizedTrack?
}

/** Capture du micro. L'implémentation Android vit dans `:data`. */
interface AudioCapture {
    /**
     * Flux froid : le micro est ouvert à la collecte et libéré à l'annulation. Émet des blocs de PCM 16 bits mono
     * à [SAMPLE_RATE] Hz. Lève `AppException(AppError.MicrophoneUnavailable)` si le micro ne peut pas être ouvert.
     */
    fun record(): Flow<ShortArray>

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
