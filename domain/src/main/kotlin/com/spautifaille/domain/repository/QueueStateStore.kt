package com.spautifaille.domain.repository

import com.spautifaille.domain.model.Track
import com.spautifaille.domain.player.RepeatMode

/** Instantané persistant de la file d'attente pour la reprise de lecture après redémarrage. */
data class QueueSnapshot(
    val tracks: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    /** Ordre de lecture en mode aléatoire (indices dans [tracks]), vide si non mélangé. */
    val shuffleOrder: List<Int> = emptyList(),
)

interface QueueStateStore {
    suspend fun save(snapshot: QueueSnapshot)
    suspend fun savePosition(currentIndex: Int, positionMs: Long)
    suspend fun load(): QueueSnapshot?
    suspend fun clear()
}
