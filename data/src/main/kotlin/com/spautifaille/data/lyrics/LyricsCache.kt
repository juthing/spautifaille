package com.spautifaille.data.lyrics

import com.spautifaille.domain.lyrics.LyricLine
import com.spautifaille.domain.lyrics.Lyrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Entrée de cache : [lyrics] `null` = « pas de paroles » (résultat négatif mis en cache). */
class CachedLyrics(val lyrics: Lyrics?)

/** Cache des paroles par identifiant de vidéo. Accès bloquants : à appeler depuis un dispatcher d'E/S. */
interface LyricsCache {
    /** Entrée valide, ou `null` (absente, expirée ou illisible). */
    fun read(videoId: String): CachedLyrics?
    fun write(videoId: String, lyrics: Lyrics?)
}

@Serializable
internal data class StoredLine(val t: Long, val s: String)

@Serializable
internal data class StoredLyrics(
    val version: Int = 1,
    val fetchedAtMs: Long,
    val kind: String,
    val source: String = "",
    val lines: List<StoredLine> = emptyList(),
    val text: String = "",
)

/**
 * Cache disque : un fichier JSON par titre (`<dossier>/<videoId>.json`), dans `cacheDir/lyrics` (purgeable par
 * le système, donc jamais source de vérité). Durées de vie : [NOT_FOUND_TTL_MS] pour « pas de paroles » et pour
 * les paroles non synchronisées (une version synchronisée peut apparaître plus tard), [FOUND_TTL_MS] sinon.
 * Les fichiers sont écrits de façon atomique ; un fichier corrompu est supprimé et compte comme absent.
 */
class FileLyricsCache(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : LyricsCache {

    private val json = Json { ignoreUnknownKeys = true }

    override fun read(videoId: String): CachedLyrics? {
        val file = fileFor(videoId)
        if (!file.isFile) return null
        val stored = try {
            json.decodeFromString<StoredLyrics>(file.readText())
        } catch (_: Exception) {
            file.delete()
            return null
        }
        val lyrics = stored.toLyrics()
        if (clock() - stored.fetchedAtMs >= ttlOf(lyrics) || stored.fetchedAtMs > clock() + TimeUnit.DAYS.toMillis(1)) {
            file.delete()
            return null
        }
        return CachedLyrics(lyrics)
    }

    override fun write(videoId: String, lyrics: Lyrics?) {
        try {
            dir.mkdirs()
            val target = fileFor(videoId)
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(StoredLyrics.serializer(), lyrics.toStored(clock())))
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
            prune()
        } catch (_: IOException) {
            // Cache best-effort : une erreur d'écriture ne doit jamais faire échouer l'affichage des paroles.
        }
    }

    private fun fileFor(videoId: String) = File(dir, videoId.replace(UNSAFE, "_") + ".json")

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES * 4 / 5).forEach { it.delete() }
    }

    private fun ttlOf(lyrics: Lyrics?): Long = when (lyrics) {
        is Lyrics.Synced, is Lyrics.Instrumental -> FOUND_TTL_MS
        is Lyrics.Plain, null -> NOT_FOUND_TTL_MS
    }

    private fun Lyrics?.toStored(now: Long): StoredLyrics = when (this) {
        null -> StoredLyrics(fetchedAtMs = now, kind = KIND_NONE)
        is Lyrics.Synced -> StoredLyrics(
            fetchedAtMs = now, kind = KIND_SYNCED, source = source, lines = lines.map { StoredLine(it.timeMs, it.text) },
        )
        is Lyrics.Plain -> StoredLyrics(fetchedAtMs = now, kind = KIND_PLAIN, source = source, text = text)
        is Lyrics.Instrumental -> StoredLyrics(fetchedAtMs = now, kind = KIND_INSTRUMENTAL, source = source)
    }

    private fun StoredLyrics.toLyrics(): Lyrics? = when (kind) {
        KIND_SYNCED -> Lyrics.Synced(lines.map { LyricLine(it.t, it.s) }, source)
        KIND_PLAIN -> Lyrics.Plain(text, source)
        KIND_INSTRUMENTAL -> Lyrics.Instrumental(source)
        else -> null
    }

    companion object {
        val NOT_FOUND_TTL_MS: Long = TimeUnit.DAYS.toMillis(7)
        val FOUND_TTL_MS: Long = TimeUnit.DAYS.toMillis(30)
        private const val MAX_FILES = 1_000
        private const val KIND_NONE = "none"
        private const val KIND_SYNCED = "synced"
        private const val KIND_PLAIN = "plain"
        private const val KIND_INSTRUMENTAL = "instrumental"
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
