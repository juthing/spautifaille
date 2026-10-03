package com.spautifaille.domain.youtube

import kotlinx.coroutines.flow.Flow

/**
 * Connexion optionnelle au compte Google / YouTube. Les erreurs sont des `AppException(AppError)`.
 */
interface AccountRepository {
    val accountState: Flow<AccountState>

    /**
     * Valide les identifiants (menu de compte InnerTube), les stocke chiffrés puis déclenche une première
     * synchronisation. Lève `AppException(AppError.YouTubeAuthRequired)` si YouTube les refuse.
     */
    suspend fun signIn(credentials: YouTubeCredentials): YouTubeAccount

    /** Efface identifiants, profil, file d'actions et instantanés ; les données locales (likes, playlists…) restent. */
    suspend fun signOut()
}

/** Synchronisation bidirectionnelle des likes, abonnements et playlists liées. */
interface LibrarySync {
    val status: Flow<SyncStatus>

    /** `true` : seuls les likes de « Musique likée » (LM) sont synchronisés ; `false` : toute la liste « J'aime » (LL). */
    val likesMusicOnly: Flow<Boolean>

    suspend fun setLikesMusicOnly(enabled: Boolean)

    /** Lance une synchronisation complète en arrière-plan (réseau requis). Sans effet si déconnecté. */
    fun syncNow()

    /**
     * À appeler au démarrage de l'app : planifie la synchro périodique (6 h) et, si la dernière synchro date de
     * plus d'une heure, en lance une. Sans effet si déconnecté.
     */
    suspend fun onAppStart()
}

/** Playlists du compte importées sous forme de playlists locales liées. */
interface YouTubePlaylistSync {
    /** Playlists de la bibliothèque du compte, avec leur état de liaison. */
    suspend fun listRemotePlaylists(): List<RemoteLibraryPlaylist>

    /** Importe les playlists choisies ; renvoie les ids des playlists locales (créées ou déjà liées). */
    suspend fun importPlaylists(remoteIds: List<String>): List<Long>

    /** Supprime le lien ; la playlist locale et la playlist YouTube restent intactes. */
    suspend fun unlink(playlistId: Long)

    /** Crée la playlist sur YouTube à partir d'une playlist locale non liée, puis la lie. */
    suspend fun publish(playlistId: Long)
}
