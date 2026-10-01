package com.spautifaille.ui.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** État du réseau utile à l'UI. */
interface NetworkMonitor {
    /**
     * `true` si le réseau par défaut est non facturé à l'usage (Wi-Fi, Ethernet…), `false` sinon
     * (données mobiles, aucun réseau). Émet la valeur courante à l'abonnement, sans doublons consécutifs.
     */
    val isUnmetered: Flow<Boolean>

    /**
     * `true` si le réseau par défaut donne accès à Internet (validé par le système), `false` hors ligne.
     * Émet la valeur courante à l'abonnement, sans doublons consécutifs. Par défaut (faux réseau de test),
     * l'appareil est considéré en ligne.
     */
    val isOnline: Flow<Boolean> get() = flowOf(true)
}

/** Implémentation via `ConnectivityManager.registerDefaultNetworkCallback`. */
class AndroidNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkMonitor {

    override val isUnmetered: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.isUnmetered())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        // Valeur initiale avant le premier rappel (qui arrive de toute façon juste après).
        trySend(manager.activeNetwork?.let(manager::getNetworkCapabilities)?.isUnmetered() ?: false)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().conflate()

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.isOnline())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(manager.activeNetwork?.let(manager::getNetworkCapabilities)?.isOnline() ?: false)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().conflate()

    private fun NetworkCapabilities.isOnline(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun NetworkCapabilities.isUnmetered(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
