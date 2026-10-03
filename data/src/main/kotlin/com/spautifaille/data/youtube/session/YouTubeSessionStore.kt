package com.spautifaille.data.youtube.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spautifaille.domain.youtube.AccountState
import com.spautifaille.domain.youtube.SyncError
import com.spautifaille.domain.youtube.YouTubeAccount
import com.spautifaille.domain.youtube.YouTubeCredentials
import java.util.Base64
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** DataStore dédié au compte YouTube (fichier `youtube_account`), exclu des sauvegardes Android. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class YouTubeDataStore

/** Identifiants et profil du compte connecté. */
internal interface YouTubeSessionStore {
    val state: Flow<AccountState>

    /** Identifiants déchiffrés, ou `null` (déconnecté, ou clé Keystore invalidée : l'état devient `ReauthRequired`). */
    suspend fun credentials(): YouTubeCredentials?

    /** Un profil est enregistré (connecté ou à reconnecter). */
    suspend fun isLinked(): Boolean

    suspend fun save(credentials: YouTubeCredentials, account: YouTubeAccount)

    suspend fun markReauthRequired()

    /** Efface identifiants et profil. */
    suspend fun clear()
}

/** État de synchronisation persistant (dernière synchro, dernière erreur, réglage des likes). */
internal interface SyncMetaStore {
    val lastSyncAt: Flow<Long?>
    val lastError: Flow<SyncError?>
    val likesMusicOnly: Flow<Boolean>

    suspend fun setLastSyncAt(at: Long)
    suspend fun setLastError(error: SyncError?)
    suspend fun setLikesMusicOnly(enabled: Boolean)
    suspend fun clear()
}

@Singleton
internal class DataStoreYouTubeSessionStore @Inject constructor(
    @YouTubeDataStore private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : YouTubeSessionStore {

    override val state: Flow<AccountState> = dataStore.data.map { prefs ->
        val account = prefs.account() ?: return@map AccountState.SignedOut
        if (prefs[REAUTH] == true || prefs[CREDENTIALS] == null) {
            AccountState.ReauthRequired(account)
        } else {
            AccountState.SignedIn(account)
        }
    }.distinctUntilChanged()

    override suspend fun credentials(): YouTubeCredentials? {
        val encoded = dataStore.data.first()[CREDENTIALS] ?: return null
        return try {
            val json = String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
            Json.decodeFromString<StoredCredentials>(json).toDomain()
        } catch (e: Exception) {
            // Clé invalidée, blob corrompu : on ne distingue pas, la reconnexion règle les deux.
            null
        }
    }

    override suspend fun isLinked(): Boolean = dataStore.data.first().account() != null

    override suspend fun save(credentials: YouTubeCredentials, account: YouTubeAccount) {
        val blob = cipher.encrypt(Json.encodeToString(StoredCredentials.from(credentials)).toByteArray(Charsets.UTF_8))
        dataStore.edit { prefs ->
            prefs[CREDENTIALS] = Base64.getEncoder().encodeToString(blob)
            prefs[ACCOUNT_NAME] = account.name
            setOrRemove(prefs, ACCOUNT_EMAIL, account.email)
            setOrRemove(prefs, ACCOUNT_HANDLE, account.handle)
            setOrRemove(prefs, ACCOUNT_AVATAR, account.avatarUrl)
            prefs[REAUTH] = false
        }
    }

    override suspend fun markReauthRequired() {
        dataStore.edit { prefs -> if (prefs[ACCOUNT_NAME] != null) prefs[REAUTH] = true }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(CREDENTIALS)
            prefs.remove(ACCOUNT_NAME)
            prefs.remove(ACCOUNT_EMAIL)
            prefs.remove(ACCOUNT_HANDLE)
            prefs.remove(ACCOUNT_AVATAR)
            prefs.remove(REAUTH)
        }
    }

    private fun Preferences.account(): YouTubeAccount? {
        val name = this[ACCOUNT_NAME] ?: return null
        return YouTubeAccount(name, this[ACCOUNT_EMAIL], this[ACCOUNT_HANDLE], this[ACCOUNT_AVATAR])
    }

    @Serializable
    private data class StoredCredentials(
        val cookie: String,
        val visitorData: String? = null,
        val dataSyncId: String? = null,
        val authUser: String = "0",
    ) {
        fun toDomain() = YouTubeCredentials(cookie, visitorData, dataSyncId, authUser)

        companion object {
            fun from(c: YouTubeCredentials) = StoredCredentials(c.cookie, c.visitorData, c.dataSyncId, c.authUser)
        }
    }
}

@Singleton
internal class DataStoreSyncMetaStore @Inject constructor(
    @YouTubeDataStore private val dataStore: DataStore<Preferences>,
) : SyncMetaStore {

    override val lastSyncAt: Flow<Long?> = dataStore.data.map { it[LAST_SYNC] }.distinctUntilChanged()

    override val lastError: Flow<SyncError?> = dataStore.data.map { prefs ->
        val message = prefs[ERROR_MESSAGE] ?: return@map null
        SyncError(prefs[ERROR_ENDPOINT].orEmpty(), message, prefs[ERROR_AT] ?: 0L)
    }.distinctUntilChanged()

    override val likesMusicOnly: Flow<Boolean> = dataStore.data.map { it[LIKES_MUSIC_ONLY] ?: false }.distinctUntilChanged()

    override suspend fun setLastSyncAt(at: Long) {
        dataStore.edit { it[LAST_SYNC] = at }
    }

    override suspend fun setLastError(error: SyncError?) {
        dataStore.edit { prefs ->
            if (error == null) {
                prefs.remove(ERROR_ENDPOINT)
                prefs.remove(ERROR_MESSAGE)
                prefs.remove(ERROR_AT)
            } else {
                prefs[ERROR_ENDPOINT] = error.endpoint
                prefs[ERROR_MESSAGE] = error.message
                prefs[ERROR_AT] = error.atMillis
            }
        }
    }

    override suspend fun setLikesMusicOnly(enabled: Boolean) {
        dataStore.edit { it[LIKES_MUSIC_ONLY] = enabled }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(LAST_SYNC)
            prefs.remove(ERROR_ENDPOINT)
            prefs.remove(ERROR_MESSAGE)
            prefs.remove(ERROR_AT)
        }
    }
}

private fun setOrRemove(prefs: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, value: String?) {
    if (value == null) prefs.remove(key) else prefs[key] = value
}

private val CREDENTIALS = stringPreferencesKey("yt_credentials")
private val ACCOUNT_NAME = stringPreferencesKey("yt_account_name")
private val ACCOUNT_EMAIL = stringPreferencesKey("yt_account_email")
private val ACCOUNT_HANDLE = stringPreferencesKey("yt_account_handle")
private val ACCOUNT_AVATAR = stringPreferencesKey("yt_account_avatar")
private val REAUTH = booleanPreferencesKey("yt_reauth_required")
private val LAST_SYNC = longPreferencesKey("yt_last_sync_at")
private val ERROR_ENDPOINT = stringPreferencesKey("yt_error_endpoint")
private val ERROR_MESSAGE = stringPreferencesKey("yt_error_message")
private val ERROR_AT = longPreferencesKey("yt_error_at")
private val LIKES_MUSIC_ONLY = booleanPreferencesKey("yt_likes_music_only")
