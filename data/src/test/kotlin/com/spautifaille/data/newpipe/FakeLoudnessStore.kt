package com.spautifaille.data.newpipe

import com.spautifaille.domain.repository.LoudnessStore

/** [LoudnessStore] en mémoire pour les tests. */
class FakeLoudnessStore : LoudnessStore {
    val values = LinkedHashMap<String, Float>()
    override fun peek(videoId: String): Float? = values[videoId]
    override suspend fun get(videoId: String): Float? = values[videoId]
    override fun put(videoId: String, loudnessDb: Float) {
        values[videoId] = loudnessDb
    }
}
