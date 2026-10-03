package com.spautifaille.data.youtube.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.spautifaille.data.youtube.testAccount
import com.spautifaille.data.youtube.testCredentials
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.SyncError
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

class SecretCipherTest {

    @Test
    fun `aller retour chiffrement dechiffrement`() {
        val cipher = AesGcmSecretCipher(newKey().let { key -> { key } })
        val plain = "cookie=SAPISID=secret; autre=é".toByteArray()
        assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun `deux chiffrements du meme texte different (IV aleatoire)`() {
        val cipher = AesGcmSecretCipher(newKey().let { key -> { key } })
        val plain = "abc".toByteArray()
        assertNotEquals(cipher.encrypt(plain).toList(), cipher.encrypt(plain).toList())
    }

    @Test
    fun `le texte chiffre ne contient pas le texte clair`() {
        val cipher = AesGcmSecretCipher(newKey().let { key -> { key } })
        val blob = cipher.encrypt("SAPISID=very-secret-value".toByteArray())
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("very-secret-value"))
    }

    @Test
    fun `une autre cle ou un blob altere echoue au dechiffrement`() {
        val cipher = AesGcmSecretCipher(newKey().let { key -> { key } })
        val other = AesGcmSecretCipher(newKey().let { key -> { key } })
        val blob = cipher.encrypt("secret".toByteArray())
        assertThrows(Exception::class.java) { other.decrypt(blob) }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
        assertThrows(Exception::class.java) { cipher.decrypt(ByteArray(5)) }
    }
}

class YouTubeSessionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        key = newKey()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "youtube_account.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store(secret: SecretKey = key) =
        DataStoreYouTubeSessionStore(dataStore, AesGcmSecretCipher { secret })

    @Test
    fun `sans compte - deconnecte et pas d identifiants`() = runTest {
        val store = store()
        assertEquals(AccountState.SignedOut, store.state.first())
        assertNull(store.credentials())
        assertFalse(store.isLinked())
    }

    @Test
    fun `enregistrement puis relecture des identifiants et du profil`() = runTest {
        val store = store()
        store.save(testCredentials, testAccount)

        assertEquals(testCredentials, store.credentials())
        assertEquals(AccountState.SignedIn(testAccount), store.state.first())
        assertTrue(store.isLinked())
    }

    @Test
    fun `les cookies ne sont jamais stockes en clair`() = runTest {
        store().save(testCredentials, testAccount)

        val raw = dataStore.data.first().asMap().values.joinToString("|") { it.toString() }
        assertFalse(raw.contains("AbCdEf123_sapisid-VALUE"))
        assertFalse(raw.contains("VISITOR"))
        // Le blob est du base64 d'un texte chiffré.
        val blob = dataStore.data.first().asMap().entries.first { it.key.name == "yt_credentials" }.value as String
        assertTrue(Base64.getDecoder().decode(blob).size > testCredentials.cookie.length)
    }

    @Test
    fun `reconnexion necessaire conserve le profil sans effacer les identifiants`() = runTest {
        val store = store()
        store.save(testCredentials, testAccount)

        store.state.test {
            assertEquals(AccountState.SignedIn(testAccount), awaitItem())
            store.markReauthRequired()
            assertEquals(AccountState.ReauthRequired(testAccount), awaitItem())
            // Une nouvelle connexion réussie lève l'indicateur.
            store.save(testCredentials, testAccount)
            assertEquals(AccountState.SignedIn(testAccount), awaitItem())
        }
    }

    @Test
    fun `reconnexion necessaire sans compte ne cree rien`() = runTest {
        val store = store()
        store.markReauthRequired()
        assertEquals(AccountState.SignedOut, store.state.first())
    }

    @Test
    fun `cle Keystore invalidee - plus d identifiants, reconnexion necessaire`() = runTest {
        store().save(testCredentials, testAccount)

        val afterKeyLoss = store(newKey())
        assertNull(afterKeyLoss.credentials())
        // Le profil reste affichable : on propose la reconnexion plutôt que de faire disparaître le compte.
        assertTrue(afterKeyLoss.isLinked())
    }

    @Test
    fun `deconnexion efface identifiants et profil`() = runTest {
        val store = store()
        store.save(testCredentials, testAccount)
        store.clear()

        assertNull(store.credentials())
        assertEquals(AccountState.SignedOut, store.state.first())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `meta de synchro - dernier passage, erreur et reglage des likes`() = runTest {
        val meta = DataStoreSyncMetaStore(dataStore)
        assertNull(meta.lastSyncAt.first())
        assertNull(meta.lastError.first())
        assertFalse(meta.likesMusicOnly.first())

        meta.setLastSyncAt(1234)
        meta.setLastError(SyncError("browse", "échec", 99))
        meta.setLikesMusicOnly(true)
        assertEquals(1234L, meta.lastSyncAt.first())
        assertEquals(SyncError("browse", "échec", 99), meta.lastError.first())
        assertTrue(meta.likesMusicOnly.first())

        meta.setLastError(null)
        assertNull(meta.lastError.first())

        // clear() efface la dernière synchro et l'erreur mais garde le réglage des likes.
        meta.setLastError(SyncError("x", "y", 1))
        meta.clear()
        assertNull(meta.lastSyncAt.first())
        assertNull(meta.lastError.first())
        assertTrue(meta.likesMusicOnly.first())
    }
}
