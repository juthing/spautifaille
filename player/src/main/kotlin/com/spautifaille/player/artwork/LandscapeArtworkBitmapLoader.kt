package com.spautifaille.player.artwork

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.spautifaille.domain.model.ArtworkUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.asListenableFuture
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/**
 * [BitmapLoader] de la `MediaSession` : c'est lui qui fournit l'image du lecteur système (carte média de l'écran
 * de verrouillage et des réglages rapides, grande icône de la notification, écran « en cours » d'Android Auto).
 * Il renvoie une **affiche paysage 16:9** (voir [LandscapeArtworkComposer]) alors que `MediaMetadata.artworkUri`
 * reste la vignette carrée : l'UI de l'application, qui lit `artworkUri`, ne change pas.
 *
 * - Les sources viennent de [ArtworkUrls.landscapeCandidates] (maxresdefault, sddefault... ou pochette HD) et sont
 *   essayées dans l'ordre via [delegate] ; une image trop petite (placeholder YouTube) est ignorée.
 * - Cache LRU en mémoire de [cacheSize] affiches (clé = première source) + déduplication des chargements en cours :
 *   la session et la notification demandent la même image presque simultanément.
 * - Si rien n'est exploitable, repli sur le chargement par défaut de `artworkUri` (comportement d'origine).
 * - Tout le travail (réseau, décodage, composition) se fait hors du thread principal.
 */
@OptIn(UnstableApi::class)
internal class LandscapeArtworkBitmapLoader(
    private val delegate: BitmapLoader,
    private val dispatcher: CoroutineDispatcher,
    private val compose: (Bitmap) -> Bitmap = LandscapeArtworkComposer::compose,
    private val cacheSize: Int = DEFAULT_CACHE_SIZE,
) : BitmapLoader {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Deferred<Bitmap?>>(cacheSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<Bitmap?>>?) = size > cacheSize
    }

    /** Annule les chargements en cours (fin du service). */
    fun release() {
        scope.cancel()
        synchronized(lock) { cache.clear() }
    }

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = delegate.loadBitmap(uri)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        // Image fournie telle quelle (pas notre cas) : comportement par défaut.
        if (metadata.artworkData != null) return delegate.decodeBitmap(metadata.artworkData!!)
        val artworkUri = metadata.artworkUri ?: return null
        val candidates = ArtworkUrls.landscapeCandidates(videoId = null, thumbnailUrl = artworkUri.toString())
        if (candidates.isEmpty()) return delegate.loadBitmap(artworkUri)
        return scope.async { poster(candidates) ?: delegate.loadBitmap(artworkUri).await() }.asListenableFuture()
    }

    /** Affiche du premier élément de [candidates] qui se charge, en cache si déjà fabriquée ; null si aucune ne marche. */
    private suspend fun poster(candidates: List<String>): Bitmap? {
        val key = candidates.first()
        val deferred = synchronized(lock) {
            cache[key]?.takeUnless { it.isCancelled } ?: scope.async { build(candidates) }.also { cache[key] = it }
        }
        val bitmap = deferred.await()
        // Échec : on ne garde pas l'échec en cache (réseau coupé, 404 transitoire...).
        if (bitmap == null) synchronized(lock) { if (cache[key] === deferred) cache.remove(key) }
        return bitmap
    }

    private suspend fun build(candidates: List<String>): Bitmap? {
        for (url in candidates) {
            val source = try {
                delegate.loadBitmap(Uri.parse(url)).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // 404 (maxresdefault absente), réseau... : source suivante
            } ?: continue
            if (source.width < MIN_SOURCE_PX || source.height < MIN_SOURCE_PX) continue
            return withContext(dispatcher) { compose(source) }
        }
        return null
    }

    companion object {
        const val DEFAULT_CACHE_SIZE = 4

        /** Les placeholders YouTube (120x90) et vignettes minuscules donneraient une affiche floue : source suivante. */
        const val MIN_SOURCE_PX = 200
    }
}
