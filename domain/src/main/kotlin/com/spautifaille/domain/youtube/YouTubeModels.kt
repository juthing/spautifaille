package com.spautifaille.domain.youtube

/**
 * Identifiants de session capturés par la WebView de connexion (cookies `music.youtube.com`, `VISITOR_DATA`,
 * `DATASYNC_ID`, index du compte Google). Secrets : ne jamais les journaliser ni les afficher.
 */
data class YouTubeCredentials(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String?,
    val authUser: String = "0",
) {
    /** Volontairement opaque : un `println` accidentel ne doit pas fuiter le cookie. */
    override fun toString(): String = "YouTubeCredentials(<masqué>)"
}

/** Profil du compte connecté (menu de compte InnerTube). */
data class YouTubeAccount(
    val name: String,
    val email: String?,
    val handle: String?,
    val avatarUrl: String?,
)

/** État de la connexion au compte YouTube. L'app fonctionne exactement comme avant en [SignedOut]. */
sealed interface AccountState {
    data object SignedOut : AccountState

    data class SignedIn(val account: YouTubeAccount) : AccountState

    /** Cookies expirés / refusés : reconnexion nécessaire, aucune donnée locale n'est effacée. */
    data class ReauthRequired(val account: YouTubeAccount) : AccountState
}

/** Dernière erreur de synchronisation, affichée par le mode diagnostic. */
data class SyncError(
    /** Endpoint InnerTube (ou étape de synchro) concerné, ex. `browse` ou `like/like`. */
    val endpoint: String,
    val message: String,
    val atMillis: Long,
)

data class SyncStatus(
    val lastSyncAt: Long? = null,
    /** Nombre d'actions distantes en attente dans la file persistée. */
    val pendingActions: Int = 0,
    val isSyncing: Boolean = false,
    val lastError: SyncError? = null,
)

/** Playlist de la bibliothèque YouTube Music du compte (proposée à l'import). */
data class RemoteLibraryPlaylist(
    /** Identifiant de playlist YouTube, sans préfixe `VL`. */
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val trackCount: Int?,
    /** Playlist créée par l'utilisateur (modifiable) ; sinon enregistrée depuis un autre auteur (lecture seule). */
    val isOwned: Boolean,
    /** Déjà importée comme playlist liée. */
    val isLinked: Boolean,
)

/** Identifiants de chaîne YouTube (`UC` + 22 caractères) et leur URL canonique, clé des abonnements locaux. */
object YouTubeChannelIds {
    private val channelUrl = Regex("""(?:youtube\.com|youtu\.be)/channel/(UC[\w-]{22})""")
    private val channelId = Regex("""UC[\w-]{22}""")

    fun isChannelId(value: String): Boolean = channelId.matchEntire(value) != null

    /** `https://www.youtube.com/channel/UC…` (ou variante mobile / music) → `UC…` ; `null` pour un `@handle` ou autre. */
    fun fromUrl(url: String): String? = channelUrl.find(url)?.groupValues?.get(1)

    fun toUrl(channelId: String): String = "https://www.youtube.com/channel/$channelId"
}
