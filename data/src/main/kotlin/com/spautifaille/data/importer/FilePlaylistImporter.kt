package com.spautifaille.data.importer

import android.content.Context
import android.net.Uri
import com.spautifaille.data.R
import com.spautifaille.data.importer.parser.ImportParseException
import com.spautifaille.data.importer.parser.PlaylistFileParsers
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.PlaylistImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Lit un fichier choisi via le Storage Access Framework (CSV / JSON) et le fait analyser par [PlaylistFileParsers]. */
class FilePlaylistImporter internal constructor(
    private val context: Context,
    private val parsers: PlaylistFileParsers,
    private val io: CoroutineDispatcher,
) : PlaylistImporter {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(context, PlaylistFileParsers(), io)

    override fun canHandle(source: ImportSource): Boolean = source is ImportSource.File

    override suspend fun read(source: ImportSource): List<ImportedPlaylist> {
        val file = source as? ImportSource.File
            ?: throw ImportException(context.getString(R.string.data_import_error_unsupported_source))
        return withContext(io) {
            val uri = Uri.parse(file.uri)
            val bytes = readBytes(uri)
            val name = file.displayName ?: uri.lastPathSegment
            try {
                parsers.parse(name, bytes)
            } catch (e: ImportParseException) {
                throw ImportException(e.reason, e)
            }
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportException(context.getString(R.string.data_import_error_cannot_open))
            return input.use { readLimited(it) }
        } catch (e: ImportException) {
            throw e
        } catch (e: SecurityException) {
            throw ImportException(context.getString(R.string.data_import_error_access_denied), e)
        } catch (e: IOException) {
            throw ImportException(context.getString(R.string.data_import_error_cannot_read), e)
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) throw ImportException(context.getString(R.string.data_import_error_file_too_large))
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /** Le message « trop volumineux » (`data_import_error_file_too_large`) annonce 20 Mo : à garder synchronisé. */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
    }
}
