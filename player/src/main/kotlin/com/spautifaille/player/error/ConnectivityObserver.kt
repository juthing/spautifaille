package com.spautifaille.player.error

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/** Surveillance de la connectivité (abstraite pour les tests). */
interface ConnectivityObserver {
    fun isOnline(): Boolean

    /** Appelle [callback] (thread principal) au prochain retour d'un réseau. Fermer le résultat pour annuler. */
    fun onAvailable(callback: () -> Unit): AutoCloseable
}

class AndroidConnectivityObserver(context: Context) : ConnectivityObserver {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun isOnline(): Boolean {
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun onAvailable(callback: () -> Unit): AutoCloseable {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post(callback)
            }
        }
        manager.registerDefaultNetworkCallback(networkCallback)
        return AutoCloseable {
            try {
                manager.unregisterNetworkCallback(networkCallback)
            } catch (_: IllegalArgumentException) {
                // déjà désinscrit
            }
        }
    }
}
