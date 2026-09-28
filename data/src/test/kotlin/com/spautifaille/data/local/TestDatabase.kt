package com.spautifaille.data.local

import android.content.Context
import androidx.room.Room
import com.spautifaille.domain.model.Track

/** Version Robolectric maximale épinglée pour les tests (voir `@Config(sdk = [...])`). */
const val TEST_SDK = 35

fun createInMemoryDatabase(context: Context): SpautifailleDatabase =
    Room.inMemoryDatabaseBuilder(context, SpautifailleDatabase::class.java)
        .allowMainThreadQueries()
        .configureSpautifaille()
        .build()

fun track(
    id: String,
    title: String = "Titre $id",
    artist: String = "Artiste",
    thumbnailUrl: String? = "https://img/$id.jpg",
) = Track(
    id = id,
    title = title,
    artist = artist,
    artistUrl = "https://www.youtube.com/channel/$artist",
    album = "Album",
    durationMs = 180_000L,
    thumbnailUrl = thumbnailUrl,
)
