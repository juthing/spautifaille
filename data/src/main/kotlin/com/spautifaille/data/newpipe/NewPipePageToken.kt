package com.spautifaille.data.newpipe

import com.spautifaille.domain.model.PageToken
import com.spautifaille.domain.model.RemotePlaylist
import org.schabi.newpipe.extractor.Page

/**
 * Jeton opaque encapsulant une [Page] NewPipe. Pour les playlists distantes, [playlist] conserve les
 * métadonnées de la première page afin de les renvoyer sur les pages suivantes.
 */
class NewPipePageToken(
    val page: Page,
    val playlist: RemotePlaylist? = null,
    /** Filtre de recherche NewPipe effectivement utilisé (peut différer du filtre demandé après repli). */
    val contentFilter: String? = null,
) : PageToken
