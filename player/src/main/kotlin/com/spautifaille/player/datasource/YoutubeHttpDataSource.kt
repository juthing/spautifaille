package com.spautifaille.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import java.util.concurrent.atomic.AtomicLong

/**
 * Construction (pure, testable) des requêtes vers les serveurs de flux YouTube (`*.googlevideo.com`).
 *
 * Portage de l'approche de `YoutubeHttpDataSource` de NewPipe : pas d'en-tête `Range` (les clients récents sont
 * refusés ou bridés avec), mais un paramètre `&range=start-end` dans l'URL, un compteur `&rn=`, et une requête
 * POST dont le corps est `{0x78, 0x00}`. Le paramètre `cpn` éventuellement présent dans l'URL est conservé tel quel.
 */
@OptIn(UnstableApi::class)
internal object YoutubeRequests {
    private const val RANGE_PARAM = "range"
    private const val RN_PARAM = "rn"

    /** Corps de la requête POST attendu par les serveurs (protobuf minimal). */
    val POST_BODY: ByteArray = byteArrayOf(0x78, 0x00)

    fun isGoogleVideoUrl(url: String): Boolean {
        val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    /**
     * Ajoute `&range=<position>-<fin>` (fin omise si [length] est inconnue) et `&rn=<requestNumber>`.
     * Les paramètres déjà présents dans l'URL ne sont jamais dupliqués.
     */
    fun buildUrl(url: String, position: Long, length: Long, requestNumber: Long): String {
        val builder = StringBuilder(url)
        if (!hasQueryParam(url, RANGE_PARAM)) {
            builder.append(if (url.contains('?')) '&' else '?').append(RANGE_PARAM).append('=').append(position).append('-')
            if (length != C.LENGTH_UNSET.toLong()) builder.append(position + length - 1)
        }
        if (!hasQueryParam(url, RN_PARAM)) {
            builder.append(if (builder.contains('?')) '&' else '?').append(RN_PARAM).append('=').append(requestNumber)
        }
        return builder.toString()
    }

    private fun hasQueryParam(url: String, name: String): Boolean {
        val query = url.substringAfter('?', "").substringBefore('#')
        return query.split('&').any { it.substringBefore('=') == name }
    }

    /**
     * Requête à effectuer pour [dataSpec]. La position et la longueur passent dans l'URL : la [DataSpec] renvoyée
     * démarre à 0 sans longueur, ce qui empêche le [OkHttpDataSource] sous-jacent d'ajouter un en-tête `Range` ou
     * de « sauter » [DataSpec.position] octets quand le serveur répond 200 (il croirait que Range a été ignoré).
     */
    fun rewrite(dataSpec: DataSpec, requestNumber: Long): DataSpec = dataSpec.buildUpon()
        .setUri(Uri.parse(buildUrl(dataSpec.uri.toString(), dataSpec.position, dataSpec.length, requestNumber)))
        .setHttpMethod(DataSpec.HTTP_METHOD_POST)
        .setHttpBody(POST_BODY)
        .setPosition(0)
        .setLength(C.LENGTH_UNSET.toLong())
        .build()
}

/**
 * [HttpDataSource] pour les flux audio YouTube, construit sur [OkHttpDataSource] (client OkHttp de l'application).
 * Les URL qui ne sont pas des URL de flux YouTube ([isYoutubeUrl]) passent inchangées vers OkHttp.
 *
 * Les réponses non 2xx (403 URL expirée, 410, 404...) lèvent [HttpDataSource.InvalidResponseCodeException] (comportement
 * d'[OkHttpDataSource]) : c'est ce que la politique d'erreur utilise pour invalider et re-résoudre l'URL.
 */
@OptIn(UnstableApi::class)
class YoutubeHttpDataSource internal constructor(
    private val delegate: OkHttpDataSource,
    private val isYoutubeUrl: (String) -> Boolean,
    private val requestCounter: AtomicLong = AtomicLong(1),
) : HttpDataSource {

    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val request = if (isYoutubeUrl(dataSpec.uri.toString())) {
            YoutubeRequests.rewrite(dataSpec, requestCounter.getAndIncrement())
        } else {
            dataSpec
        }
        return delegate.open(request)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun getResponseCode(): Int = delegate.responseCode

    override fun close() = delegate.close()

    override fun setRequestProperty(name: String, value: String) = delegate.setRequestProperty(name, value)

    override fun clearRequestProperty(name: String) = delegate.clearRequestProperty(name)

    override fun clearAllRequestProperties() = delegate.clearAllRequestProperties()

    @OptIn(UnstableApi::class)
    class Factory @JvmOverloads constructor(
        callFactory: Call.Factory,
        private val isYoutubeUrl: (String) -> Boolean = YoutubeRequests::isGoogleVideoUrl,
    ) : HttpDataSource.Factory {
        private val delegateFactory = OkHttpDataSource.Factory(callFactory)

        override fun createDataSource(): YoutubeHttpDataSource =
            YoutubeHttpDataSource(delegateFactory.createDataSource(), isYoutubeUrl)

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory {
            delegateFactory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }

        fun setUserAgent(userAgent: String?): Factory {
            delegateFactory.setUserAgent(userAgent)
            return this
        }
    }
}
