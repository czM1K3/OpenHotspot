package org.openhotspot.tablet.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openhotspot.tablet.auto.NetworkMonitor

/**
 * "Online" = the system's default network has validated internet. The default-network callback is
 * used on purpose: it reports what the system routes through, not our process-bound hotspot network.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val flow = MutableStateFlow(false)
    override val online: StateFlow<Boolean> get() = flow
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            flow.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        override fun onLost(network: Network) { flow.value = false }
    }

    @Synchronized fun start() {
        if (registered) return
        cm.registerDefaultNetworkCallback(callback)
        registered = true
    }

    @Synchronized fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }
}
