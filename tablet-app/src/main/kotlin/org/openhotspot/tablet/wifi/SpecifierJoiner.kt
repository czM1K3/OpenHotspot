package org.openhotspot.tablet.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.Security
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Joins with [WifiNetworkSpecifier]. Android shows its own confirmation dialog (the user picks Connect);
 * the app must be in the foreground for that. The connection exists only while our callback is
 * registered, and only this app can use it, so we bind the process to it. To leave, [WifiLink.release].
 */
class SpecifierJoiner(context: Context) : WifiJoiner {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    override suspend fun join(spec: WifiSpec, timeoutMs: Long): WifiLink {
        if (!wifi.isWifiEnabled) throw WifiJoinException(WifiProblem.WIFI_OFF)
        val specifier = build(spec)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        var callback: ConnectivityManager.NetworkCallback? = null
        var link: SpecifierLink? = null
        val result = withTimeoutOrNull(timeoutMs + 2_000) {
            suspendCancellableCoroutine<Result<SpecifierLink>> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // Bind first: from here on our own sockets and DNS use the hotspot.
                        cm.bindProcessToNetwork(network)
                        val l = SpecifierLink(cm, network, this)
                        link = l
                        if (cont.isActive) cont.resume(Result.success(l))
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(Result.failure(WifiJoinException(WifiProblem.NOT_AVAILABLE)))
                    }

                    override fun onLost(network: Network) {
                        link?.notifyLost()
                        if (cont.isActive) cont.resume(Result.failure(WifiJoinException(WifiProblem.LOST)))
                    }
                }
                callback = cb
                try {
                    cm.requestNetwork(request, cb, timeoutMs.toInt())
                } catch (e: SecurityException) {
                    cont.resumeWithException(WifiJoinException(WifiProblem.REJECTED, "Android refused the request (${e.message})"))
                } catch (e: RuntimeException) { // for example "too many requests"
                    cont.resumeWithException(WifiJoinException(WifiProblem.REJECTED, e.message))
                }
                cont.invokeOnCancellation { cleanup(cb, link) }
            }
        }
        val outcome = result ?: run {
            callback?.let { cleanup(it, link) }
            throw WifiJoinException(WifiProblem.TIMEOUT)
        }
        return outcome.getOrElse {
            callback?.let { cb -> cleanup(cb, link) }
            throw it
        }
    }

    private fun cleanup(cb: ConnectivityManager.NetworkCallback, link: SpecifierLink?) {
        if (link != null) link.release() else runCatching { cm.unregisterNetworkCallback(cb) }
    }

    internal fun build(spec: WifiSpec): WifiNetworkSpecifier {
        val b = WifiNetworkSpecifier.Builder()
        b.setSsid(ssidText(spec))
        when (spec.security) {
            Security.WPA2_PSK, Security.WPA2_WPA3 -> b.setWpa2Passphrase(spec.passphrase) // transition mode accepts WPA2
            Security.WPA3_SAE -> b.setWpa3Passphrase(spec.passphrase)
            Security.OPEN -> throw WifiJoinException(WifiProblem.UNSUPPORTED_SECURITY, "The phone offered an open network, which this app never joins.")
        }
        if (spec.hidden) b.setIsHiddenSsid(true)
        return b.build()
    }

    private class SpecifierLink(
        private val cm: ConnectivityManager,
        private val network: Network,
        private val callback: ConnectivityManager.NetworkCallback,
    ) : WifiLink {
        @Volatile private var lostListener: (() -> Unit)? = null
        @Volatile private var released = false

        override suspend fun probe(timeoutMs: Long) = NetworkProbe.probe(network, timeoutMs = timeoutMs.toInt())

        override fun setLostListener(listener: (() -> Unit)?) { lostListener = listener }

        fun notifyLost() { if (!released) lostListener?.invoke() }

        override fun release() {
            if (released) return
            released = true
            lostListener = null
            runCatching { if (cm.boundNetworkForProcess == network) cm.bindProcessToNetwork(null) }
            runCatching { cm.unregisterNetworkCallback(callback) } // this is what disconnects a specifier network
        }
    }
}

/**
 * The SSID as text. `WifiNetworkSpecifier.Builder` (checked against the API 36 stubs) only takes a
 * String, so an SSID that is not valid UTF-8 cannot be joined this way. `WifiNetworkSuggestion`
 * can take raw bytes on API 33+ (see [SuggestionJoiner]).
 */
internal fun ssidText(spec: WifiSpec): String = try {
    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(spec.ssid)).toString()
} catch (e: java.nio.charset.CharacterCodingException) {
    throw WifiJoinException(WifiProblem.UNSUPPORTED_SSID, "The hotspot name is not valid text. Rename the phone's hotspot to plain text, or use \"Share with all apps\" on Android 13 or newer.")
}
