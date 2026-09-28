package com.spautifaille.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.track
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class LibraryRepositoryTest {

    private lateinit var db: SpautifailleDatabase
    private lateinit var library: LibraryRepositoryImpl
    private lateinit var playlists: PlaylistRepositoryImpl
    private var now = 10_000L

    @Before
    fun setUp() {
        db = createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        val clock = { ++now }
        library = LibraryRepositoryImpl(
            db, db.trackDao(), db.playlistDao(), db.historyDao(), db.subscriptionDao(), clock,
        )
        playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // region Likes

    @Test
    fun toggleLikeAddsThenRemovesAndReturnsNewState() = runTest {
        val t = track("a")

        assertTrue(library.toggleLike(t))
        assertTrue(library.isLiked("a"))
        assertTrue(playlists.containsTrack(Playlist.LIKED_ID, "a"))

        assertFalse(library.toggleLike(t))
        assertFalse(library.isLiked("a"))
        assertFalse(playlists.containsTrack(Playlist.LIKED_ID, "a"))
    }

    @Test
    fun unlikeKeepsLikedPositionsContiguous() = runTest {
        listOf("a", "b", "c").forEach { library.setLiked(track(it), true) }

        library.setLiked(track("b"), false)

        val liked = playlists.observePlaylist(Playlist.LIKED_ID).first()!!
        assertEquals(listOf("a", "c"), liked.entries.map { it.track.id })
        assertEquals(listOf(0, 1), liked.entries.map { it.position })
    }

    @Test
    fun setLikedIsIdempotent() = runTest {
        library.setLiked(track("a"), true)
        library.setLiked(track("a"), true)
        assertEquals(1, playlists.observePlaylist(Playlist.LIKED_ID).first()!!.entries.size)

        library.setLiked(track("a"), false)
        library.setLiked(track("a"), false)
        assertEquals(0, playlists.observePlaylist(Playlist.LIKED_ID).first()!!.entries.size)
    }

    @Test
    fun observeLikedIdsAndIsLikedReactToChanges() = runTest {
        library.observeLikedIds().test {
            assertEquals(emptySet<String>(), awaitItem())
            library.toggleLike(track("a"))
            assertEquals(setOf("a"), awaitItem())
            library.toggleLike(track("b"))
            assertEquals(setOf("a", "b"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        library.observeIsLiked("a").test {
            assertTrue(awaitItem())
            library.toggleLike(track("a"))
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun recentlyLikedIsOrderedNewestFirst() = runTest {
        library.setLiked(track("a"), true)
        library.setLiked(track("b"), true)
        library.setLiked(track("c"), true)

        assertEquals(listOf("c", "b"), library.recentlyLiked(2).map { it.id })
    }

    @Test
    fun libraryTrackIdsIsDistinctAcrossAllPlaylists() = runTest {
        library.setLiked(track("a"), true)
        playlists.create("P1", listOf(track("a"), track("b")))
        playlists.create("P2", listOf(track("b"), track("c")))
        // Un titre seulement joué n'est pas dans la bibliothèque.
        library.recordPlay(track("z"))

        assertEquals(setOf("a", "b", "c"), library.libraryTrackIds())
    }

    // endregion

    // region History

    @Test
    fun recordPlayUpsertsTrackAndObservesNewestFirst() = runTest {
        library.recordPlay(track("a"), playedAt = 100)
        library.recordPlay(track("b"), playedAt = 300)
        library.recordPlay(track("a"), playedAt = 200)

        val history = library.observeHistory(10).first()
        assertEquals(listOf("b", "a", "a"), history.map { it.track.id })
        assertEquals(listOf(300L, 200L, 100L), history.map { it.playedAt })
        assertEquals(2, library.observeHistory(2).first().size)
        assertNotNull(db.trackDao().get("a"))
    }

    @Test
    fun removeHistoryEntryAndClear() = runTest {
        library.recordPlay(track("a"), 1)
        library.recordPlay(track("b"), 2)
        val first = library.observeHistory(10).first().first()

        library.removeHistoryEntry(first.id)
        assertEquals(listOf("a"), library.observeHistory(10).first().map { it.track.id })

        library.clearHistory()
        assertTrue(library.observeHistory(10).first().isEmpty())
    }

    @Test
    fun recentlyPlayedIdsRespectsSinceBound() = runTest {
        library.recordPlay(track("old"), playedAt = 100)
        library.recordPlay(track("new"), playedAt = 1_000)
        library.recordPlay(track("new"), playedAt = 1_100)

        assertEquals(setOf("new"), library.recentlyPlayedIds(sinceMs = 500))
        assertEquals(setOf("old", "new"), library.recentlyPlayedIds(sinceMs = 0))
    }

    @Test
    fun mostPlayedGroupsByTrackWithCountAndLastPlayed() = runTest {
        repeat(3) { library.recordPlay(track("a"), playedAt = 100L + it) } // 3x, dernier = 102
        repeat(2) { library.recordPlay(track("b"), playedAt = 200L + it) } // 2x, dernier = 201
        library.recordPlay(track("c"), playedAt = 300)                     // 1x

        val top = library.mostPlayed(limit = 2)
        assertEquals(listOf("a", "b"), top.map { it.track.id })
        assertEquals(listOf(3, 2), top.map { it.count })
        assertEquals(listOf(102L, 201L), top.map { it.lastPlayedAt })

        val recent = library.mostPlayed(limit = 10, sinceMs = 150)
        assertEquals(listOf("b", "c"), recent.map { it.track.id })
        assertEquals(listOf(2, 1), recent.map { it.count })
    }

    // endregion

    // region Subscriptions

    @Test
    fun subscribeAndUnsubscribe() = runTest {
        val a = Artist(url = "https://yt/ch/a", name = "Zebra", avatarUrl = "https://img/a.jpg")
        val b = Artist(url = "https://yt/ch/b", name = "alpha")

        library.observeSubscriptions().test {
            assertTrue(awaitItem().isEmpty())
            library.subscribe(a)
            awaitItem()
            library.subscribe(b)
            // Tri insensible à la casse par nom.
            assertEquals(listOf("alpha", "Zebra"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(library.observeIsSubscribed(a.url).first())

        library.unsubscribe(a.url)
        assertFalse(library.observeIsSubscribed(a.url).first())
        assertEquals(listOf(b.url), library.observeSubscriptions().first().map { it.url })
    }

    @Test
    fun resubscribeUpdatesNameAndAvatar() = runTest {
        library.subscribe(Artist(url = "u", name = "Ancien"))
        library.subscribe(Artist(url = "u", name = "Nouveau", avatarUrl = "https://img/n.jpg"))

        val subs = library.observeSubscriptions().first()
        assertEquals(1, subs.size)
        assertEquals("Nouveau", subs.single().name)
        assertEquals("https://img/n.jpg", subs.single().avatarUrl)
    }

    // endregion
}
