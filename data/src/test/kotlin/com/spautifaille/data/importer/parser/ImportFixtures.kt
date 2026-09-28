package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportedTrack

/** Accès aux fixtures `src/test/resources/import/`. */
internal object ImportFixtures {
    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("import/$name")) { "Fixture introuvable : $name" }
            .use { it.readBytes() }

    fun text(name: String): String = ImportTextDecoder.decode(bytes(name))
}

internal fun track(
    title: String,
    vararg artists: String,
    album: String? = null,
    durationMs: Long? = null,
    isrc: String? = null,
    youtubeId: String? = null,
) = ImportedTrack(title, artists.toList(), album, durationMs, isrc, youtubeId)
