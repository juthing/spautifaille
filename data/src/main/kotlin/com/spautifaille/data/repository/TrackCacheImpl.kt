package com.spautifaille.data.repository

import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.toDomain
import com.spautifaille.data.local.toEntity
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.TrackCache
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackCacheImpl internal constructor(
    private val trackDao: TrackDao,
    private val clock: () -> Long,
) : TrackCache {

    @Inject
    constructor(trackDao: TrackDao) : this(trackDao, System::currentTimeMillis)

    override suspend fun get(id: String): Track? = trackDao.get(id)?.toDomain()

    override suspend fun put(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val now = clock()
        trackDao.upsertAll(tracks.distinctBy { it.id }.map { it.toEntity(now) })
    }
}
