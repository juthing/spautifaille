package com.spautifaille.data.youtube.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.data.local.createInMemoryDatabase
import com.spautifaille.data.local.track
import com.spautifaille.data.repository.LibraryRepositoryImpl
import com.spautifaille.data.repository.PlaylistRepositoryImpl
import com.spautifaille.data.youtube.FakeMetaStore
import com.spautifaille.data.youtube.FakeScheduler
import com.spautifaille.data.youtube.FakeSessionStore
import com.spautifaille.data.youtube.FakeYouTubeRemote
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.YouTubeChannelIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class PendingActionsTest {

    private lateinit var db: SpautifailleDatabase
    private lateinit var sessions: FakeSessionStore
    private lateinit var scheduler: FakeScheduler
    private lateinit var remote: FakeYouTubeRemote
    private lateinit var recorder: PendingActionRecorder
    private lateinit var processor: PendingActionProcessor
    private lateinit var library: LibraryRepositoryImpl
    private lateinit var playlists: PlaylistRepositoryImpl
    private var now = 100L

    private val channelA = "UCxEqaQWosMHaTih-tgzDqug"

    @Before
    fun setUp() {
        db = createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        sessions = FakeSessionStore()
        scheduler = FakeScheduler()
        remote = FakeYouTubeRemote()
        val clock = { ++now }
        recorder = PendingActionRecorder(sessions, db.youTubeSyncDao(), scheduler, clock)
        val engine = YouTubeSyncEngine(
            db, db.trackDao(), db.playlistDao(), db.subscriptionDao(), db.youTubeSyncDao(), remote, FakeMetaStore(), clock,
        )
        processor = PendingActionProcessor(db.youTubeSyncDao(), remote, engine)
        // Repositories branchés sur le vrai enregistreur : on vérifie le chemin complet bouton -> file d'actions.
        library = LibraryRepositoryImpl(db, db.trackDao(), db.playlistDao(), db.historyDao(), db.subscriptionDao(), clock)
            .also { it.remoteSync = recorder }
        playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), clock).also { it.remoteSync = recorder }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val dao get() = db.youTubeSyncDao()

    private suspend fun pending() = dao.pendingActions().map { Triple(it.kind, it.target, it.enabled) }

    // region Enregistrement des actions

    @Test
    fun `un like enfile une action et planifie l envoi`() = runTest {
        library.setLiked(track("a"), true)

        assertEquals(listOf(Triple("LIKE", "a", true)), pending())
        assertEquals(1, scheduler.flushes)
    }

    @Test
    fun `like puis unlike du meme titre - une seule action, la derniere intention`() = runTest {
        library.setLiked(track("a"), true)
        library.setLiked(track("a"), false)

        assertEquals(listOf(Triple("LIKE", "a", false)), pending())
    }

    @Test
    fun `un like sans changement n enfile rien`() = runTest {
        library.setLiked(track("a"), true)
        dao.deleteAllActions()

        library.setLiked(track("a"), true)

        assertTrue(pending().isEmpty())
    }

    @Test
    fun `sans compte connecte, rien n est enfile`() = runTest {
        sessions.stateFlow.value = AccountState.SignedOut

        library.toggleLike(track("a"))
        library.subscribe(Artist(YouTubeChannelIds.toUrl(channelA), "A"))

        assertTrue(pending().isEmpty())
        assertEquals(0, scheduler.flushes)
    }

    @Test
    fun `compte a reconnecter - les actions restent en file pour plus tard`() = runTest {
        sessions.stateFlow.value = AccountState.ReauthRequired(com.spautifaille.data.youtube.testAccount)

        library.setLiked(track("a"), true)

        assertEquals(1, pending().size)
    }

    @Test
    fun `abonnement et desabonnement avec identifiant de chaine`() = runTest {
        library.subscribe(Artist(YouTubeChannelIds.toUrl(channelA), "A"))
        assertEquals(listOf(Triple("SUBSCRIPTION", channelA, true)), pending())

        library.unsubscribe(YouTubeChannelIds.toUrl(channelA))
        assertEquals(listOf(Triple("SUBSCRIPTION", channelA, false)), pending())
    }

    @Test
    fun `un abonnement sans identifiant de chaine n est pas synchronisable`() = runTest {
        library.subscribe(Artist("https://www.youtube.com/@handle", "Handle"))
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `retirer un titre de Titres likes equivaut a un unlike, l ajouter a un like`() = runTest {
        playlists.addTracks(Playlist.LIKED_ID, listOf(track("a")))
        assertEquals(listOf(Triple("LIKE", "a", true)), pending())

        playlists.removeEntry(Playlist.LIKED_ID, dao.entryRows(Playlist.LIKED_ID).single().entryId)
        assertEquals(listOf(Triple("LIKE", "a", false)), pending())
    }

    @Test
    fun `une playlist non liee n enfile rien, une playlist liee enfile une synchro coalescee`() = runTest {
        val plain = playlists.create("Locale", listOf(track("a")))
        playlists.addTracks(plain, listOf(track("b")))
        assertTrue(pending().isEmpty())

        remote.addPlaylist("PL1", "Liée", owned = true, "x")
        val linked = importLinked("PL1")
        playlists.addTracks(linked, listOf(track("y")))
        playlists.addTracks(linked, listOf(track("z")))
        playlists.moveEntry(linked, 0, 1)
        playlists.removeEntry(linked, dao.entryRows(linked).first().entryId)

        assertEquals(listOf(Triple("PLAYLIST_SYNC", linked.toString(), true)), pending())
    }

    @Test
    fun `une playlist en lecture seule n enfile rien`() = runTest {
        remote.addPlaylist("PL2", "Miroir", owned = false, "x")
        val mirror = importLinked("PL2")

        playlists.addTracks(mirror, listOf(track("y")))

        assertTrue(pending().isEmpty())
    }

    @Test
    fun `supprimer une playlist liee n enfile rien et nettoie le lien`() = runTest {
        remote.addPlaylist("PL1", "Liée", owned = true, "x")
        val linked = importLinked("PL1")

        playlists.delete(linked)

        assertTrue(pending().isEmpty())
        assertTrue(dao.allLinks().isEmpty())
    }

    // endregion

    // region Envoi de la file

    @Test
    fun `flush - envoie likes et abonnements puis vide la file`() = runTest {
        library.setLiked(track("a"), true)
        library.setLiked(track("b"), true)
        library.subscribe(Artist(YouTubeChannelIds.toUrl(channelA), "A"))

        val failures = processor.flush()

        assertTrue(failures.isEmpty())
        assertEquals(setOf("a", "b"), remote.likedMusic)
        assertEquals(setOf(channelA), remote.channels.keys)
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `flush - un unlike en file est envoye`() = runTest {
        remote.likedMusic += "a"
        library.setLiked(track("a"), true)
        library.setLiked(track("a"), false)

        processor.flush()

        assertEquals(emptySet<String>(), remote.likedMusic)
        assertEquals(listOf("unlike:a"), remote.callsStartingWith("unlike:"))
        assertTrue(remote.callsStartingWith("like:").isEmpty())
    }

    @Test
    fun `flush - une erreur reseau interrompt et garde l action en file`() = runTest {
        library.setLiked(track("a"), true)
        library.setLiked(track("b"), true)
        remote.failures += "like:a" to AppError.Network

        val error = runCatching { processor.flush() }.exceptionOrNull() as AppException

        assertEquals(AppError.Network, error.error)
        assertEquals(2, pending().size)
        assertEquals(1, dao.pendingActions().first().attempts)
    }

    @Test
    fun `flush - reconnexion necessaire interrompt et garde la file`() = runTest {
        library.setLiked(track("a"), true)
        remote.failures += "like:a" to AppError.YouTubeAuthRequired

        val error = runCatching { processor.flush() }.exceptionOrNull() as AppException

        assertEquals(AppError.YouTubeAuthRequired, error.error)
        assertEquals(1, pending().size)
    }

    @Test
    fun `flush - une erreur permanente est consignee puis l action est abandonnee apres 5 essais`() = runTest {
        library.setLiked(track("a"), true)
        library.setLiked(track("b"), true)
        remote.failures += "like:a" to AppError.YouTubeSyncFailed("like/like", "HTTP 400")

        repeat(PendingActionProcessor.MAX_ATTEMPTS - 1) {
            val failures = processor.flush()
            assertEquals(1, failures.size)
            assertEquals("LIKE a", failures.single().step)
        }
        // La seconde action (b) n'est pas bloquée par la première.
        assertEquals(setOf("b"), remote.likedMusic)
        assertEquals(listOf("a"), dao.pendingActions().map { it.target })
        assertEquals(PendingActionProcessor.MAX_ATTEMPTS - 1, dao.pendingActions().single().attempts)

        processor.flush()
        assertTrue("abandonnée", pending().isEmpty())
    }

    @Test
    fun `flush - une synchro de playlist liee en file est executee`() = runTest {
        remote.addPlaylist("PL1", "Liée", owned = true, "x")
        val linked = importLinked("PL1")
        playlists.addTracks(linked, listOf(track("y")))

        processor.flush()

        assertEquals(listOf("x", "y"), remote.videoIds("PL1"))
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `flush - une playlist devenue non liee est ignoree`() = runTest {
        dao.enqueue(ActionKind.PLAYLIST_SYNC, "999", true, 1)

        processor.flush()

        assertTrue(pending().isEmpty())
    }

    @Test
    fun `flush - le compteur d actions en attente suit la file`() = runTest {
        assertEquals(0, dao.observePendingCount().first())
        library.setLiked(track("a"), true)
        assertEquals(1, dao.observePendingCount().first())
        processor.flush()
        assertEquals(0, dao.observePendingCount().first())
    }

    // endregion

    private suspend fun importLinked(remoteId: String): Long {
        val engine = YouTubeSyncEngine(
            db, db.trackDao(), db.playlistDao(), db.subscriptionDao(), dao, remote, FakeMetaStore(), { ++now },
        )
        return engine.importPlaylist(remoteId)
    }
}
