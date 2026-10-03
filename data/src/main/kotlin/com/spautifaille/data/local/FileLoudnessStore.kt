package com.spautifaille.data.local

import android.content.Context
import android.util.Log
import com.spautifaille.domain.di.ApplicationScope
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.repository.LoudnessStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LoudnessStore] dans un petit fichier texte (`filesDir/loudness.tsv`, une ligne `videoId<TAB>dB` par titre).
 *
 * Volontairement hors Room : pas de schéma ni de migration pour une donnée aussi légère (quelques dizaines
 * d'octets par titre), et lecture mémoire immédiate pour le lecteur. Conserve au plus [maxEntries] titres
 * (les plus récemment vus). Les écritures sont regroupées ([saveDelayMs]) et atomiques (fichier temporaire
 * puis renommage) ; un fichier illisible ou corrompu est simplement ignoré.
 */
@Singleton
class FileLoudnessStore internal constructor(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val maxEntries: Int,
    private val saveDelayMs: Long,
) : LoudnessStore {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(File(context.filesDir, FILE_NAME), scope, io, MAX_ENTRIES, SAVE_DELAY_MS)

    private val lock = Any()
    private val loadLock = Any()

    /** Ordre d'insertion = ordre de fraîcheur : l'entrée la plus ancienne est évincée en premier. */
    private var entries = LinkedHashMap<String, Float>()
    private var loaded = false
    private var saveJob: Job? = null

    override fun peek(videoId: String): Float? = synchronized(lock) { entries[videoId] }

    override suspend fun get(videoId: String): Float? = withContext(io) {
        ensureLoaded()
        synchronized(lock) { entries[videoId] }
    }

    override fun put(videoId: String, loudnessDb: Float) {
        if (videoId.isBlank() || !loudnessDb.isFinite() || videoId.any { it == '\t' || it == '\n' || it == '\r' }) return
        val changed = synchronized(lock) {
            val previous = entries.remove(videoId)
            entries[videoId] = loudnessDb
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
            previous != loudnessDb
        }
        if (changed) scheduleSave()
    }

    /** Lit le fichier une seule fois ; les valeurs écrites entre-temps (plus récentes) l'emportent. */
    private fun ensureLoaded() {
        if (synchronized(lock) { loaded }) return
        synchronized(loadLock) {
            if (synchronized(lock) { loaded }) return
            val fromDisk = readFile()
            synchronized(lock) {
                val merged = LinkedHashMap<String, Float>(fromDisk)
                entries.forEach { (id, value) ->
                    merged.remove(id)
                    merged[id] = value
                }
                while (merged.size > maxEntries) merged.remove(merged.keys.first())
                entries = merged
                loaded = true
            }
        }
    }

    private fun readFile(): Map<String, Float> {
        val result = LinkedHashMap<String, Float>()
        try {
            if (!file.isFile) return result
            file.useLines { lines ->
                for (line in lines) {
                    val separator = line.indexOf('\t')
                    if (separator <= 0) continue
                    val value = line.substring(separator + 1).toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
                    result[line.substring(0, separator)] = value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lecture des niveaux sonores impossible", e)
        }
        return result
    }

    private fun scheduleSave() {
        synchronized(lock) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch(io) {
                delay(saveDelayMs)
                save()
            }
        }
    }

    private fun save() {
        try {
            // Une sauvegarde avant le premier chargement écraserait les anciennes entrées : on charge d'abord.
            ensureLoaded()
            val snapshot = synchronized(lock) {
                saveJob = null
                LinkedHashMap(entries)
            }
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.bufferedWriter().use { writer ->
                snapshot.forEach { (id, value) -> writer.write("$id\t$value\n") }
            }
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Écriture des niveaux sonores impossible", e)
        }
    }

    companion object {
        const val FILE_NAME = "loudness.tsv"
        const val MAX_ENTRIES = 20_000
        const val SAVE_DELAY_MS = 3_000L
        private const val TAG = "LoudnessStore"
    }
}
