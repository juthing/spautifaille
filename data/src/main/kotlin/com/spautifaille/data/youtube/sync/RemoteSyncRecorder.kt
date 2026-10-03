package com.spautifaille.data.youtube.sync

/**
 * Point d'accroche des repositories locaux (likes, abonnements, playlists) vers la synchronisation du compte
 * YouTube. Appelé **après** l'écriture locale ; les implémentations ne lèvent jamais.
 */
interface RemoteSyncRecorder {
    suspend fun likeChanged(videoId: String, liked: Boolean)

    /** [artistUrl] : URL de chaîne telle que stockée localement. */
    suspend fun subscriptionChanged(artistUrl: String, subscribed: Boolean)

    /** Ajout, suppression ou déplacement d'entrées dans la playlist locale [playlistId] (sans effet si elle n'est pas liée). */
    suspend fun playlistChanged(playlistId: Long)

    /** Sans compte : aucune action. Valeur par défaut des repositories (tests, app sans YouTube). */
    object None : RemoteSyncRecorder {
        override suspend fun likeChanged(videoId: String, liked: Boolean) = Unit
        override suspend fun subscriptionChanged(artistUrl: String, subscribed: Boolean) = Unit
        override suspend fun playlistChanged(playlistId: Long) = Unit
    }
}
