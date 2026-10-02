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
import com.spautifaille.data.youtube.FakeYouTubeRemote
import com.spautifaille.data.youtube.api.RemoteChannel
import com.spautifaille.data.youtube.api.RemoteLibraryEntry
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Artist
import com.spautifaille.domain.model.Playlist
import com.spautifaille.domain.youtube.YouTubeChannelIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class YouTubeSyncEngineTest {

    private lateinit var db: SpautifailleDatabase
    private lateinit var remote: FakeYouTubeRemote
    private lateinit var meta: FakeMetaStore
    private lateinit var engine: YouTubeSyncEngine
    private lateinit var library: LibraryRepositoryImpl
    private lateinit var playlists: PlaylistRepositoryImpl
    private var now = 1_000L

    private val channelA = "UCxEqaQWosMHaTih-tgzDqug"
    private val channelB = "UC2Eotb0QaPkaJI4Cw4oHZ6Q"
    private val channelC = "UCUX1nzkfAaWhXMYSAlMMbmw"

    @Before
    fun setUp() {
        db = createInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        remote = FakeYouTubeRemote()
        meta = FakeMetaStore()
        val clock = { ++now }
        engine = YouTubeSyncEngine(
            db, db.trackDao(), db.playlistDao(), db.subscriptionDao(), db.youTubeSyncDao(), remote, meta, clock,
        )
        library = LibraryRepositoryImpl(
            db, db.trackDao(), db.playlistDao(), db.historyDao(), db.subscriptionDao(), clock,
        )
        playlists = PlaylistRepositoryImpl(db, db.playlistDao(), db.trackDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val dao get() = db.youTubeSyncDao()

    private suspend fun likedIds(): Set<String> = dao.trackIds(Playlist.LIKED_ID).toSet()

    private suspend fun localIds(playlistId: Long): List<String> = dao.entryRows(playlistId).map { it.trackId }

    private suspend fun like(vararg ids: String) = ids.forEach { library.setLiked(track(it), true) }

    private fun channelUrl(id: String) = YouTubeChannelIds.toUrl(id)

    // region Likes

    @Test
    fun `likes - premier lien, union des deux ensembles`() = runTest {
        like("a", "b")
        remote.likedMusic += listOf("b", "c")

        val failures = engine.syncAll()

        assertTrue(failures.isEmpty())
        assertEquals(setOf("a", "b", "c"), likedIds())
        assertEquals(setOf("a", "b", "c"), remote.likedMusic)
        assertEquals(listOf("like:a"), remote.callsStartingWith("like:"))
        assertTrue(remote.callsStartingWith("unlike:").isEmpty())
        assertEquals(listOf("a", "b", "c"), dao.snapshot("LIKES")!!.sorted())
    }

    @Test
    fun `likes importes du plus ancien au plus recent`() = runTest {
        remote.likedMusic += listOf("old", "mid", "new")

        engine.syncAll()

        // La dernière ajoutée est la plus récente : elle sera en tête de « Titres likés » (tri par date d'ajout).
        assertEquals(listOf("old", "mid", "new"), localIds(Playlist.LIKED_ID))
        assertNotNull(db.trackDao().get("new"))
    }

    @Test
    fun `likes - un like retire en local est retire a distance`() = runTest {
        like("a", "b")
        engine.syncAll()
        remote.calls.clear()

        library.setLiked(track("a"), false)
        engine.syncAll()

        assertEquals(listOf("unlike:a"), remote.callsStartingWith("unlike:"))
        assertEquals(setOf("b"), remote.likedMusic)
        assertEquals(setOf("b"), likedIds())
    }

    @Test
    fun `likes - un like retire a distance est retire en local`() = runTest {
        like("a", "b")
        engine.syncAll()

        remote.likedMusic -= "a"
        val failures = engine.syncAll()

        assertEquals(emptyList<StepFailure>(), failures)
        assertEquals(setOf("b"), likedIds())
        assertEquals("a et b poussés une seule fois, à la première synchro", 2, remote.callsStartingWith("like:").size)
    }

    @Test
    fun `likes - ajouts des deux cotes entre deux synchros`() = runTest {
        like("a")
        engine.syncAll()

        like("local")
        remote.likedMusic += "distant"
        engine.syncAll()

        assertEquals(setOf("a", "local", "distant"), likedIds())
        assertEquals(setOf("a", "local", "distant"), remote.likedMusic)
    }

    @Test
    fun `likes - mode musique seule - un like non musical n est ni supprime ni repousse`() = runTest {
        meta.musicOnly.value = true
        remote.nonMusic += "video"
        like("video", "music")

        engine.syncAll()
        assertEquals(setOf("music"), remote.likedMusic)
        assertEquals(setOf("video"), remote.likedOther)
        engine.syncAll()
        engine.syncAll()

        assertEquals(setOf("video", "music"), likedIds())
        assertEquals("poussé une seule fois", 1, remote.callsStartingWith("like:video").size)
        assertEquals(1, remote.callsStartingWith("like:music").size)
        assertTrue(remote.callsStartingWith("likedAll").isEmpty())
    }

    @Test
    fun `likes - mode musique seule - un like musical retire a distance est retire en local`() = runTest {
        meta.musicOnly.value = true
        like("video", "music")
        remote.nonMusic += "video"
        engine.syncAll()
        engine.syncAll()

        remote.likedMusic -= "music"
        engine.syncAll()

        assertEquals(setOf("video"), likedIds())
    }

    @Test
    fun `likes - toute la liste - repli sur Musique likee si LL n est pas servie`() = runTest {
        remote.likedAllSupported = false
        remote.likedMusic += "a"

        val failures = engine.syncAll()

        assertTrue(failures.isEmpty())
        assertEquals(setOf("a"), likedIds())
        assertEquals("LM", dao.snapshot("LIKES_MODE")!!.single())
    }

    @Test
    fun `likes - un changement de mode ne supprime rien en local`() = runTest {
        remote.nonMusic += "video"
        remote.likedMusic += "music"
        remote.likedOther += "video"
        engine.syncAll()
        assertEquals(setOf("music", "video"), likedIds())

        meta.musicOnly.value = true
        engine.syncAll()

        assertEquals(setOf("music", "video"), likedIds())
    }

    @Test
    fun `likes - une erreur reseau interrompt tout sans toucher au local ni a l instantane`() = runTest {
        like("a")
        remote.failures += "like:" to AppError.Network

        val error = runCatching { engine.syncAll() }.exceptionOrNull() as AppException

        assertEquals(AppError.Network, error.error)
        assertNull(dao.snapshot("LIKES"))
        assertEquals(setOf("a"), likedIds())
    }

    // endregion

    // region Abonnements

    @Test
    fun `abonnements - premier lien, union, et chaine sans identifiant conservee`() = runTest {
        library.subscribe(Artist(channelUrl(channelA), "A"))
        library.subscribe(Artist("https://www.youtube.com/@handle", "Handle"))
        remote.channels[channelC] = RemoteChannel(channelC, "C", "https://img/c")

        val failures = engine.syncAll()

        assertTrue(failures.isEmpty())
        val local = db.subscriptionDao().observeAll().first().map { it.url }.toSet()
        assertEquals(setOf(channelUrl(channelA), "https://www.youtube.com/@handle", channelUrl(channelC)), local)
        assertEquals(setOf(channelA, channelC), remote.channels.keys)
        assertEquals("https://img/c", db.subscriptionDao().observeAll().first().first { it.url == channelUrl(channelC) }.avatarUrl)
        assertTrue(remote.callsStartingWith("subscribe:").none { it.contains("handle") })
    }

    @Test
    fun `abonnements - suppressions propagees dans les deux sens`() = runTest {
        library.subscribe(Artist(channelUrl(channelA), "A"))
        library.subscribe(Artist(channelUrl(channelB), "B"))
        remote.channels[channelB] = RemoteChannel(channelB, "B", null)
        engine.syncAll()
        assertEquals(setOf(channelA, channelB), remote.channels.keys)

        library.unsubscribe(channelUrl(channelA))
        remote.channels.remove(channelB)
        engine.syncAll()

        assertEquals(emptySet<String>(), remote.channels.keys)
        assertEquals(emptyList<String>(), db.subscriptionDao().observeAll().first().map { it.url })
        assertEquals(listOf("unsubscribe:$channelA"), remote.callsStartingWith("unsubscribe:"))
    }

    @Test
    fun `abonnements - une chaine invisible cote distant n est pas supprimee`() = runTest {
        // Chaîne non musicale : poussée à l'abonnement mais jamais listée par la bibliothèque YouTube Music.
        library.subscribe(Artist(channelUrl(channelA), "A"))
        remote.hideFromListing = setOf(channelA)

        engine.syncAll()
        engine.syncAll()

        assertEquals(listOf(channelUrl(channelA)), db.subscriptionDao().observeAll().first().map { it.url })
        assertEquals(1, remote.callsStartingWith("subscribe:").size)
    }

    // endregion

    // region Playlists liées

    @Test
    fun `import - cree la playlist locale liee avec les setVideoId`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "x", "y", "z")

        val id = engine.importPlaylist("PL1")

        assertEquals(listOf("x", "y", "z"), localIds(id))
        val link = dao.link(id)!!
        assertEquals("PL1", link.youtubePlaylistId)
        assertFalse(link.readOnly)
        assertNotNull(link.lastSyncedAt)
        val entryLinks = dao.entryLinks(id)
        assertEquals(3, entryLinks.size)
        assertEquals(remote.playlists.getValue("PL1").items.map { it.setVideoId }, dao.entryRows(id).map { row -> entryLinks.first { it.entryId == row.entryId }.setVideoId })
        assertEquals(listOf("x", "y", "z"), dao.snapshot("PL:$id"))
        assertEquals("Road trip", db.playlistDao().observePlaylist(id).first()!!.name)
        assertTrue(db.playlistDao().observePlaylist(id).first()!!.isLinked)
        assertNotNull(db.trackDao().get("x"))
    }

    @Test
    fun `import - une playlist deja liee n est pas dupliquee`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "x")
        val first = engine.importPlaylist("PL1")
        val second = engine.importPlaylist("PL1")
        assertEquals(first, second)
        assertEquals(1, remote.callsStartingWith("playlist:PL1").size)
    }

    @Test
    fun `import - playlist d un autre auteur en lecture seule`() = runTest {
        remote.addPlaylist("PL2", "Chill", owned = false, "p", "q")
        val id = engine.importPlaylist("PL2")
        assertTrue(dao.link(id)!!.readOnly)
    }

    @Test
    fun `playlist liee - un ajout local est pousse vers YouTube`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "x", "y")
        val id = engine.importPlaylist("PL1")

        playlists.addTracks(id, listOf(track("w")))
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("x", "y", "w"), remote.videoIds("PL1"))
        assertEquals(listOf("x", "y", "w"), localIds(id))
        // Le nouvel élément reçoit son setVideoId distant.
        val wRow = dao.entryRows(id).last()
        assertEquals(remote.playlists.getValue("PL1").items.last().setVideoId, dao.setVideoIdOf(wRow.entryId))
        assertEquals(listOf("x", "y", "w"), dao.snapshot("PL:$id"))
    }

    @Test
    fun `playlist liee - une suppression locale est poussee avec le setVideoId`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "x", "y", "z")
        val id = engine.importPlaylist("PL1")
        val yEntry = dao.entryRows(id)[1].entryId
        val ySetVideoId = remote.playlists.getValue("PL1").items[1].setVideoId

        playlists.removeEntry(id, yEntry)
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("x", "z"), remote.videoIds("PL1"))
        assertTrue(remote.calls.any { it == "remove:PL1:y" })
        assertNotNull(ySetVideoId)
        assertEquals(listOf("x", "z"), localIds(id))
    }

    @Test
    fun `playlist liee - un reordonnancement local est pousse par deplacements`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a", "b", "c", "d")
        val id = engine.importPlaylist("PL1")

        playlists.moveEntry(id, 3, 0)
        assertEquals(listOf("d", "a", "b", "c"), localIds(id))
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("d", "a", "b", "c"), remote.videoIds("PL1"))
        assertEquals(listOf("d", "a", "b", "c"), localIds(id))
        assertTrue(remote.callsStartingWith("move:").isNotEmpty())
        assertEquals(listOf("d", "a", "b", "c"), dao.snapshot("PL:$id"))
    }

    @Test
    fun `playlist liee - ajouts et suppressions distants sont appliques en local`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a", "b", "c")
        val id = engine.importPlaylist("PL1")

        val items = remote.playlists.getValue("PL1").items
        items.removeAll { it.videoId == "b" }
        remote.addToPlaylist("PL1", listOf("n"), false)
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("a", "c", "n"), localIds(id))
        assertEquals(listOf("a", "c", "n"), remote.videoIds("PL1"))
    }

    @Test
    fun `playlist liee - reordonnancement distant seul applique en local`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a", "b", "c")
        val id = engine.importPlaylist("PL1")

        remote.playlists.getValue("PL1").items.apply { add(0, removeAt(2)) }
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("c", "a", "b"), localIds(id))
        assertTrue(remote.callsStartingWith("move:").isEmpty())
    }

    @Test
    fun `playlist liee - reordonnancement des deux cotes, le distant gagne`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a", "b", "c")
        val id = engine.importPlaylist("PL1")

        playlists.moveEntry(id, 2, 0) // local : c a b
        remote.playlists.getValue("PL1").items.apply { add(0, removeAt(1)) } // distant : b a c
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("b", "a", "c"), localIds(id))
        assertEquals(listOf("b", "a", "c"), remote.videoIds("PL1"))
    }

    @Test
    fun `playlist liee - changements croises (ajout local, suppression distante)`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a", "b", "c")
        val id = engine.importPlaylist("PL1")

        playlists.addTracks(id, listOf(track("n")))
        remote.playlists.getValue("PL1").items.removeAll { it.videoId == "a" }
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("b", "c", "n"), localIds(id))
        assertEquals(listOf("b", "c", "n"), remote.videoIds("PL1"))
    }

    @Test
    fun `playlist liee - doublons conserves`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a")
        val id = engine.importPlaylist("PL1")

        playlists.addTracks(id, listOf(track("a")))
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("a", "a"), remote.videoIds("PL1"))
        assertEquals(listOf("a", "a"), localIds(id))
    }

    @Test
    fun `playlist en lecture seule - le distant ecrase les modifications locales, rien n est pousse`() = runTest {
        remote.addPlaylist("PL2", "Chill", owned = false, "p", "q")
        val id = engine.importPlaylist("PL2")
        remote.calls.clear()

        playlists.addTracks(id, listOf(track("intrus")))
        playlists.removeEntry(id, dao.entryRows(id).first().entryId)
        engine.syncLinkedPlaylist(id)

        assertEquals(listOf("p", "q"), localIds(id))
        assertTrue(remote.calls.none { it.startsWith("add:") || it.startsWith("remove:") || it.startsWith("move:") })
    }

    @Test
    fun `playlist liee - modification locale pendant la synchro, rien n est ecrase`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a", "b")
        val id = engine.importPlaylist("PL1")
        playlists.addTracks(id, listOf(track("n")))
        // Pendant l'envoi vers YouTube, l'utilisateur retire « a » en local.
        remote.hook = { call ->
            if (call.startsWith("add:")) {
                remote.hook = null
                playlists.removeEntry(id, dao.entryRows(id).first().entryId)
            }
        }

        engine.syncLinkedPlaylist(id)

        assertEquals("la modification de l'utilisateur est conservée", listOf("b", "n"), localIds(id))
        assertEquals("l'instantané n'avance pas", listOf("a", "b"), dao.snapshot("PL:$id"))

        // La synchro suivante (déjà en file en vrai) converge : « a » est retiré des deux côtés.
        engine.syncLinkedPlaylist(id)
        assertEquals(listOf("b", "n"), remote.videoIds("PL1"))
        assertEquals(listOf("b", "n"), localIds(id))
    }

    @Test
    fun `unlink - supprime le lien, les setVideoId et l instantane, garde la playlist`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a", "b")
        val id = engine.importPlaylist("PL1")

        engine.unlink(id)

        assertNull(dao.link(id))
        assertTrue(dao.entryLinks(id).isEmpty())
        assertNull(dao.snapshot("PL:$id"))
        assertEquals(listOf("a", "b"), localIds(id))
        assertFalse(db.playlistDao().observePlaylist(id).first()!!.isLinked)
    }

    @Test
    fun `publication - cree la playlist sur YouTube et la lie`() = runTest {
        val id = playlists.create("Ma sélection", listOf(track("a"), track("b")))

        engine.publish(id)

        val link = dao.link(id)!!
        assertEquals(listOf("a", "b"), remote.videoIds(link.youtubePlaylistId))
        assertFalse(link.readOnly)
        assertEquals(2, dao.entryLinks(id).size)
        assertEquals(listOf("a", "b"), dao.snapshot("PL:$id"))
        assertEquals(1, remote.callsStartingWith("create:").size)

        // Déjà liée : sans effet.
        engine.publish(id)
        assertEquals(1, remote.callsStartingWith("create:").size)
    }

    @Test
    fun `publication - la playlist des titres likes n est jamais publiee`() = runTest {
        like("a")
        engine.publish(Playlist.LIKED_ID)
        assertTrue(remote.callsStartingWith("create:").isEmpty())
    }

    @Test
    fun `liste distante - marque les playlists deja liees`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a")
        remote.libraryEntries += RemoteLibraryEntry("PL1", "Road trip", null, 1, true)
        remote.libraryEntries += RemoteLibraryEntry("PL9", "Autre", null, 3, false)
        engine.importPlaylist("PL1")

        val list = engine.listRemotePlaylists()

        assertEquals(listOf("PL1" to true, "PL9" to false), list.map { it.id to it.isLinked })
    }

    // endregion

    // region Robustesse

    @Test
    fun `une playlist en echec n empeche pas les autres etapes`() = runTest {
        like("a")
        remote.addPlaylist("PL1", "Road trip", owned = true, "x")
        val failing = engine.importPlaylist("PL1")
        remote.addPlaylist("PL3", "Autre", owned = true, "y")
        val healthy = engine.importPlaylist("PL3")
        val syncedBefore = dao.link(healthy)!!.lastSyncedAt!!
        remote.playlists.remove("PL1")

        val failures = engine.syncAll()

        assertEquals(1, failures.size)
        assertEquals("playlist PL1", failures.single().step)
        assertTrue(failures.single().error is AppError.YouTubeSyncFailed)
        assertEquals(setOf("a"), remote.likedMusic)
        assertNotNull(dao.link(failing))
        assertTrue("la playlist saine est resynchronisée", dao.link(healthy)!!.lastSyncedAt!! > syncedBefore)
    }

    @Test
    fun `reconnexion necessaire interrompt la synchro`() = runTest {
        remote.failures += "likedAll" to AppError.YouTubeAuthRequired

        val error = runCatching { engine.syncAll() }.exceptionOrNull() as AppException

        assertEquals(AppError.YouTubeAuthRequired, error.error)
        assertTrue(remote.callsStartingWith("subscriptions").isEmpty())
    }

    @Test
    fun `les instantanes de playlists supprimees sont purges`() = runTest {
        remote.addPlaylist("PL1", "Road trip", owned = true, "a")
        val id = engine.importPlaylist("PL1")
        assertNotNull(dao.snapshot("PL:$id"))

        db.playlistDao().delete(id)
        engine.syncAll()

        assertNull(dao.snapshot("PL:$id"))
    }

    // endregion
}
