package com.spautifaille.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.spautifaille.data.newpipe.NewPipeInitializer
import com.spautifaille.domain.di.ApplicationScope
import com.spautifaille.domain.repository.DownloadRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class SpautifailleApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    // Provider : la fabrique n'est lue qu'à l'initialisation de WorkManager, jamais avant l'injection des champs.
    @Inject lateinit var workerFactory: Provider<HiltWorkerFactory>
    @Inject lateinit var newPipeInitializer: NewPipeInitializer
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    // Lazy : instancié explicitement à la fin de onCreate (démarre l'index des fichiers hors ligne et la
    // réconciliation des téléchargements), sans dépendre de l'ordre d'injection des champs.
    @Inject lateinit var downloadRepository: dagger.Lazy<DownloadRepository>

    override fun onCreate() {
        super.onCreate()
        // Initialisation de NewPipe hors du thread principal (le repository la garantit aussi paresseusement).
        appScope.launch(Dispatchers.IO) { newPipeInitializer.init() }
        downloadRepository.get()
    }

    // WorkManager est initialisé à la demande avec la fabrique Hilt (initialiseur par défaut retiré du manifeste).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory.get())
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    // Coil partage le client OkHttp de l'app ; pochettes en cache mémoire + disque.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
