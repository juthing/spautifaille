package com.spautifaille.data.importer.parser

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportedPlaylist

/**
 * Lecteur d'un format de fichier d'import (contenu déjà décodé en texte). Pur JVM, sans API Android.
 *
 * [canParse] est un « sniff » rapide et ne lève jamais d'exception ; [parse] peut lever
 * [ImportParseException] si le contenu est corrompu.
 */
interface PlaylistFileParser {
    val format: ImportFormat

    /** Vrai si [content] (et éventuellement l'extension de [fileName]) ressemble à ce format. */
    fun canParse(fileName: String?, content: String): Boolean

    /** Renvoie les playlists lues ; les lignes sans titre ni id YouTube sont ignorées. */
    fun parse(fileName: String?, content: String): List<ImportedPlaylist>
}

/** Fichier illisible / format non reconnu. [reason] est un message utilisateur en français. */
class ImportParseException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)
