package com.spautifaille.data.newpipe

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import javax.inject.Inject
import javax.inject.Singleton

/** Initialise NewPipeExtractor une seule fois (idempotent, thread-safe). */
@Singleton
class NewPipeInitializer @Inject constructor(
    private val downloader: Downloader,
) {
    @Volatile
    private var initialized = false
    private val lock = Any()

    val isInitialized: Boolean get() = initialized

    fun init() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            NewPipe.init(downloader, Localization("fr", "FR"), ContentCountry("FR"))
            initialized = true
        }
    }

    /** Appelé paresseusement par le repository avant tout appel à un extracteur. */
    fun ensureInitialized() = init()
}
