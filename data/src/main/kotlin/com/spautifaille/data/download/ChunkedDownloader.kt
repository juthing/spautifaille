package com.spautifaille.data.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Réponse HTTP inattendue pendant un téléchargement. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** URL de flux + en-têtes obligatoires (User-Agent VisionOS...). Durée de vie courte : jamais persistée. */
data class DownloadSource(val url: String, val headers: Map<String, String>)

/**
 * Boucle de téléchargement par blocs, sans dépendance à Android, WorkManager ni NewPipe.
 *
 * Chaque bloc de [chunkSize] octets est demandé par un `POST` (corps `{0x78, 0x00}`) sur l'URL portant
 * `&range=<début>-<fin>&rn=<n>` (cf. [RangeUrl]) : YouTube bride les longues requêtes uniques.
 * Le téléchargement reprend à `startOffset` (le fichier `sink` est tronqué à cette taille puis complété).
 *
 * - HTTP 403 : `onForbidden` est appelé (une seule fois de suite) pour obtenir une URL fraîche et le même
 *   bloc est rejoué ; sans nouvelle source, [HttpStatusException] est levée.
 * - Autres statuts hors 200/206 et erreurs d'E/S : levés tels quels (le worker décide de rejouer).
 * - Taille totale inconnue (`totalLength == null`) : on lit jusqu'à un bloc incomplet, vide ou un 416.
 */
class ChunkedDownloader(
    private val client: OkHttpClient,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(chunkSize > 0) { "chunkSize doit être positif" }
    }

    /**
     * @param onProgress appelé après chaque tampon écrit avec (octets présents dans [sink], taille totale connue).
     * @return nombre d'octets présents dans [sink] à la fin.
     */
    suspend fun download(
        source: DownloadSource,
        sink: File,
        startOffset: Long,
        totalLength: Long?,
        onForbidden: suspend () -> DownloadSource? = { null },
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Long = withContext(io) {
        require(startOffset >= 0) { "startOffset négatif" }
        require(totalLength == null || startOffset <= totalLength) { "startOffset au-delà de la taille totale" }
        var current = source
        var offset = startOffset
        var requestNumber = 0
        var refreshedSinceProgress = false
        val buffer = ByteArray(BUFFER_SIZE)

        RandomAccessFile(sink, "rw").use { file ->
            file.setLength(offset)
            file.seek(offset)
            while (totalLength == null || offset < totalLength) {
                ensureActive()
                val end = (if (totalLength != null) minOf(offset + chunkSize, totalLength) else offset + chunkSize) - 1
                val expected = end - offset + 1
                val request = Request.Builder()
                    .url(RangeUrl.build(current.url, offset, end, requestNumber++))
                    .apply { current.headers.forEach { (name, value) -> header(name, value) } }
                    .post(POST_BODY.toRequestBody(null))
                    .build()

                val received = client.newCall(request).await().use { response ->
                    when (response.code) {
                        200, 206 -> {
                            var got = 0L
                            val body = response.body.source()
                            while (got < expected) {
                                ensureActive()
                                val n = body.read(buffer, 0, minOf(buffer.size.toLong(), expected - got).toInt())
                                if (n == -1) break
                                file.write(buffer, 0, n)
                                got += n
                                offset += n
                                onProgress(offset, totalLength)
                            }
                            got
                        }
                        403 -> {
                            val fresh = if (refreshedSinceProgress) null else onForbidden()
                            if (fresh == null) throw HttpStatusException(403)
                            current = fresh
                            refreshedSinceProgress = true
                            REPLAY
                        }
                        // Fin de flux atteinte pile sur une frontière de bloc quand la taille est inconnue.
                        416 -> if (totalLength == null && offset > startOffset) 0L else throw HttpStatusException(416)
                        else -> throw HttpStatusException(response.code)
                    }
                }

                when {
                    received == REPLAY -> Unit // nouvelle source : on rejoue le même bloc
                    received == 0L -> {
                        if (totalLength != null) throw IOException("Réponse vide à l'offset $offset")
                        break
                    }
                    else -> {
                        refreshedSinceProgress = false
                        if (totalLength == null && received < expected) break
                    }
                }
            }
        }
        if (totalLength != null && offset != totalLength) {
            throw IOException("Téléchargement incomplet : $offset / $totalLength octets")
        }
        offset
    }

    private suspend fun Call.await(): Response {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, _, _ -> response.close() }
                    }
                },
            )
        }
    }

    companion object {
        /** ~1,5 Mo : compromis entre le nombre de requêtes et le bridage YouTube des longues requêtes. */
        const val DEFAULT_CHUNK_SIZE: Long = 1_536L * 1024
        private const val BUFFER_SIZE = 32 * 1024
        private const val REPLAY = -1L

        /** Corps attendu par googlevideo pour les requêtes POST de lecture. */
        internal val POST_BODY = byteArrayOf(0x78, 0x00)
    }
}
