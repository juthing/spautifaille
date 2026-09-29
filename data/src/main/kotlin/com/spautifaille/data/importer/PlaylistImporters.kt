package com.spautifaille.data.importer

import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.importer.PlaylistImporter
import javax.inject.Inject

/** Registre des importers : le premier qui sait traiter la source la lit. */
class PlaylistImporters @Inject constructor(
    private val importers: List<@JvmSuppressWildcards PlaylistImporter>,
) {
    fun find(source: ImportSource): PlaylistImporter? = importers.firstOrNull { it.canHandle(source) }
}
