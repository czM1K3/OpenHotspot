package org.openhotspot.tablet.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiSsid
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.Security
import kotlin.coroutines.resume

/**
 * Joins with a [WifiNetworkSuggestion]. Unlike the specifier, the whole device then uses the network,
 * but the system alone decides when (and whether) to connect, the first suggestion needs the user's
 * approval (a notification), and Android sends no reliable "connected to your suggestion" signal
 * without location permission. We therefore wait for a Wi-Fi network that was not there before.
 */
class SuggestionJoiner(context: Context) : WifiJoiner {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    override suspend fun join(spec: WifiSpec, timeoutMs: Long): WifiLink {
        if (!wifi.isWifiEnabled) throw WifiJoinException(WifiProblem.WIFI_OFF)
        val suggestion = build(spec)
        @Suppress("DEPRECATION") // allNetworks is the only synchronous snapshot; deprecated in 31 but still works
        val before = cm.allNetworks.filter { isWifi(it) }.toSet()

        // A leftover suggestion from an earlier session would make "add" report a duplicate.
        wifi.removeNetworkSuggestions(wifi.networkSuggestions.filter { it.ssidMatches(suggestion) })
        when (val status = wifi.addNetworkSuggestions(listOf(suggestion))) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> Unit
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> throw WifiJoinException(
                WifiProblem.REJECTED, "Network suggestions are disallowed for this app. Allow them in the system's Wi-Fi settings.",
            )
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN -> throw WifiJoinException(WifiProblem.REJECTED, "Restricted by an administrator.")
            else -> throw WifiJoinException(WifiProblem.REJECTED, "Android refused the suggestion (status $status).")
        }

        var callback: ConnectivityManager.NetworkCallback? = null
        val network = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Network> { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (network !in before && cont.isActive) cont.resume(network)
                        }
                    }
                    callback = cb
                    cm.registerNetworkCallback(
                        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
                    )
                    cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
                }
            }
        } catch (e: Exception) {
            removeSuggestion(suggestion)
            throw e
        }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        if (network == null) {
            removeSuggestion(suggestion)
            throw WifiJoinException(WifiProblem.TIMEOUT)
        }
        return SuggestionLink(cm, wifi, network, suggestion)
    }

    private fun isWifi(n: Network) = cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun removeSuggestion(s: WifiNetworkSuggestion) { runCatching { wifi.removeNetworkSuggestions(listOf(s)) } }

    private fun WifiNetworkSuggestion.ssidMatches(other: WifiNetworkSuggestion) = this == other

    internal fun build(spec: WifiSpec): WifiNetworkSuggestion {
        val b = WifiNetworkSuggestion.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) b.setWifiSsid(WifiSsid.fromBytes(spec.ssid)) // exact bytes
        else b.setSsid(ssidText(spec))
        when (spec.security) {
            Security.WPA2_PSK, Security.WPA2_WPA3 -> b.setWpa2Passphrase(spec.passphrase)
            Security.WPA3_SAE -> b.setWpa3Passphrase(spec.passphrase)
            Security.OPEN -> throw WifiJoinException(WifiProblem.UNSUPPORTED_SECURITY, "The phone offered an open network, which this app never joins.")
        }
        if (spec.hidden) b.setIsHiddenSsid(true)
        return b.build()
    }

    private class SuggestionLink(
        private val cm: ConnectivityManager,
        private val wifi: WifiManager,
        private val network: Network,
        private val suggestion: WifiNetworkSuggestion,
    ) : WifiLink {
        @Volatile private var lostCallback: ConnectivityManager.NetworkCallback? = null

        override suspend fun probe(timeoutMs: Long) = NetworkProbe.probe(network, timeoutMs = timeoutMs.toInt())

        override fun setLostListener(listener: (() -> Unit)?) {
            lostCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            lostCallback = null
            if (listener == null) return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(lost: Network) { if (lost == network) listener() }
            }
            lostCallback = cb
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        }

        /** Removing the suggestion makes the system disconnect from it. */
        override fun release() {
            setLostListener(null)
            runCatching { wifi.removeNetworkSuggestions(listOf(suggestion)) }
        }
    }
}
