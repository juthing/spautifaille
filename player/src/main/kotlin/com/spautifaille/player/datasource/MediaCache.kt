package com.spautifaille.player.datasource

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.spautifaille.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache disque de streaming (`cacheDir/media`), LRU, taille = réglage `streamCacheSizeMb` (512 Mo par défaut).
 *
 * Singleton de process : il n'existe qu'un seul `SimpleCache` par dossier, et il survit aux recréations du service.
 * Créé paresseusement au premier accès (thread de chargement/lecture) pour ne pas faire d'E/S au démarrage du service.
 * La taille est lue au premier accès ; un changement de réglage s'applique au prochain lancement du process.
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    val cache: SimpleCache by lazy {
        val maxBytes = runBlocking { settings.current().streamCacheSizeMb }
            .coerceAtLeast(MIN_SIZE_MB) * BYTES_PER_MB
        SimpleCache(
            File(context.cacheDir, DIRECTORY),
            LeastRecentlyUsedCacheEvictor(maxBytes),
            StandaloneDatabaseProvider(context),
        )
    }

    private companion object {
        const val DIRECTORY = "media"
        const val MIN_SIZE_MB = 32
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
