package com.spautifaille.player.datasource

import android.net.Uri

/** URI stable d'un titre : `spautifaille://track/<videoId>`. C'est aussi la base de la clé de cache. */
object TrackUri {
    const val SCHEME = "spautifaille"
    private const val HOST = "track"

    fun build(videoId: String): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(videoId).build()

    /** Renvoie l'identifiant vidéo si [uri] est une URI de titre, sinon null. */
    fun videoId(uri: Uri?): String? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() }
    }
}
