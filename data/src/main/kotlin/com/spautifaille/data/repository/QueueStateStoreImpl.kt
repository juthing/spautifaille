package com.spautifaille.data.repository

import androidx.room.withTransaction
import com.spautifaille.data.local.QueueDao
import com.spautifaille.data.local.QueueItemEntity
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.toDomain
import com.spautifaille.data.local.toEntity
import com.spautifaille.data.local.toSnapshot
import com.spautifaille.data.local.toStateEntity
import com.spautifaille.domain.repository.QueueSnapshot
import com.spautifaille.domain.repository.QueueStateStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueStateStoreImpl internal constructor(
    private val db: SpautifailleDatabase,
    private val queueDao: QueueDao,
    private val trackDao: TrackDao,
    private val clock: () -> Long,
) : QueueStateStore {

    @Inject
    constructor(
        db: SpautifailleDatabase,
        queueDao: QueueDao,
        trackDao: TrackDao,
    ) : this(db, queueDao, trackDao, System::currentTimeMillis)

    override suspend fun save(snapshot: QueueSnapshot) {
        db.withTransaction {
            val now = clock()
            trackDao.upsertAll(snapshot.tracks.distinctBy { it.id }.map { it.toEntity(now) })
            // Les uid ne sont pas exposés par QueueSnapshot : le lecteur en régénère à la reprise.
            val items = snapshot.tracks.mapIndexed { index, track ->
                QueueItemEntity(position = index, trackId = track.id, uid = UUID.randomUUID().toString())
            }
            queueDao.replaceAll(items, snapshot.toStateEntity())
        }
    }

    override suspend fun savePosition(currentIndex: Int, positionMs: Long) {
        queueDao.updatePosition(currentIndex, positionMs)
    }

    override suspend fun load(): QueueSnapshot? = db.withTransaction {
        val state = queueDao.loadState() ?: return@withTransaction null
        val items = queueDao.loadItems()
        if (items.isEmpty()) return@withTransaction null
        state.toSnapshot(items.map { it.track.toDomain() })
    }

    override suspend fun clear() = queueDao.clear()
}
