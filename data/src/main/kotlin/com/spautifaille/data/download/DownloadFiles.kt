package com.spautifaille.data.download

import java.io.File

/**
 * Nommage et manipulation des fichiers téléchargés : `filesDir/downloads/<id>.<ext>` une fois complet,
 * `<id>.<ext>.part` pendant le téléchargement (repris à sa taille courante).
 */
internal object DownloadFiles {
    const val DIRECTORY = "downloads"
    const val PART_SUFFIX = ".part"

    /** Sous-dossier de `cacheDir` où le lecteur place son `SimpleCache` (mesuré, jamais modifié ici). */
    const val PLAYER_CACHE_DIRECTORY = "media"

    /** Un identifiant YouTube ne contient que `[A-Za-z0-9_-]` ; tout le reste est écarté du nom de fichier. */
    fun safeName(trackId: String): String = trackId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** `audio/webm` -> `webm`, `audio/mp4` -> `m4a`. */
    fun extensionFor(mimeType: String?): String {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            mime.contains("webm") -> "webm"
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
            mime.contains("ogg") || mime.contains("opus") -> "opus"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            else -> "audio"
        }
    }

    fun finalFile(dir: File, trackId: String, extension: String) = File(dir, "${safeName(trackId)}.$extension")

    fun partFile(dir: File, trackId: String, extension: String) =
        File(dir, "${safeName(trackId)}.$extension$PART_SUFFIX")

    /** Tous les fichiers (complet et/ou `.part`) associés à [trackId] dans [dir]. */
    fun filesOf(dir: File, trackId: String): List<File> {
        val prefix = "${safeName(trackId)}."
        return dir.listFiles { file -> file.isFile && file.name.startsWith(prefix) }?.toList().orEmpty()
    }

    fun partsOf(dir: File, trackId: String): List<File> = filesOf(dir, trackId).filter { it.name.endsWith(PART_SUFFIX) }

    fun deleteAllOf(dir: File, trackId: String) {
        filesOf(dir, trackId).forEach { it.delete() }
    }

    /** Taille cumulée récursive de [dir] (0 si absent). */
    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isDirectory) current.listFiles()?.let { stack.addAll(it) } else total += current.length()
        }
        return total
    }
}
