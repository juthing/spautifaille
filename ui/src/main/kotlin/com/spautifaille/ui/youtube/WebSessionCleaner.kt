package com.spautifaille.ui.youtube

import android.webkit.CookieManager
import android.webkit.WebStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Efface les traces de la connexion Google dans la WebView (cookies, stockage web). Abstraite pour les tests. */
interface WebSessionCleaner {
    suspend fun clear()
}

@Singleton
class AndroidWebSessionCleaner @Inject constructor() : WebSessionCleaner {
    override suspend fun clear() {
        // Les API WebView exigent le thread principal.
        withContext(Dispatchers.Main) {
            runCatching {
                suspendCancellableCoroutine { continuation ->
                    CookieManager.getInstance().removeAllCookies { continuation.resume(Unit) }
                }
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
    }
}
