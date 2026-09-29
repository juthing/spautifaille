package com.spautifaille.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.File

/**
 * Aiguillage de plus haut niveau : un titre téléchargé est lu directement depuis son fichier, SANS passer par le
 * `CacheDataSource` (sinon les octets d'un fichier local seraient recopiés dans le cache de streaming).
 * Tout le reste part vers [remote] (cache -> résolution -> réseau).
 */
@OptIn(UnstableApi::class)
class OfflineFirstDataSource(
    private val localFilePath: (videoId: String) -> String?,
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = TrackUri.videoId(dataSpec.uri)?.let(localFilePath)?.takeIf { File(it).isFile }
        return if (path != null) {
            active = local
            local.open(dataSpec.buildUpon().setUri(Uri.fromFile(File(path))).build())
        } else {
            active = remote
            remote.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "read() before open()" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(
        private val localFilePath: (String) -> String?,
        private val remoteFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            OfflineFirstDataSource(localFilePath, FileDataSource(), remoteFactory.createDataSource())
    }
}
