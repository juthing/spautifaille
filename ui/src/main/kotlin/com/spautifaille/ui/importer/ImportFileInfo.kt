package com.spautifaille.ui.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import com.spautifaille.domain.di.IoDispatcher
import javax.inject.Inject

/** Nom et type d'un fichier choisi via le Storage Access Framework. */
data class ImportFileInfo(val displayName: String?, val mimeType: String?)

/** Lit les métadonnées d'un `content://` URI (hors composable, pour garder les ViewModels testables). */
interface ImportFileInfoProvider {
    suspend fun describe(uri: String): ImportFileInfo
}

class ContentResolverImportFileInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ImportFileInfoProvider {

    override suspend fun describe(uri: String): ImportFileInfo = withContext(io) {
        val parsed = Uri.parse(uri)
        val name = try {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        val type = try {
            context.contentResolver.getType(parsed)
        } catch (e: Exception) {
            null
        }
        ImportFileInfo(displayName = name ?: parsed.lastPathSegment, mimeType = type)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImportUiModule {
    @Binds
    abstract fun bindImportFileInfoProvider(impl: ContentResolverImportFileInfoProvider): ImportFileInfoProvider
}
