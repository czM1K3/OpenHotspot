package org.openhotspot.tablet.wifi

import android.net.Network
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One HTTP request over a specific [Network] (not the system default), the same idea as Android's own
 * captive-portal check: a 204 answer means the internet is reachable, a redirect or a 200 page means
 * something intercepts it.
 */
object NetworkProbe {
    /** What Android itself fetches for validation; plain HTTP on purpose (a login page cannot answer HTTPS). */
    const val DEFAULT_URL = "http://connectivitycheck.gstatic.com/generate_204"

    suspend fun probe(network: Network, url: String = DEFAULT_URL, timeoutMs: Int): ProbeResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = network.openConnection(URL(url)) as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            interpret(conn.responseCode)
        } catch (e: Exception) {
            ProbeResult.Offline(e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** Pure, so it is unit tested. */
    fun interpret(httpStatus: Int): ProbeResult = when (httpStatus) {
        204 -> ProbeResult.Online
        in 200..399 -> ProbeResult.CaptivePortal
        else -> ProbeResult.Offline("HTTP $httpStatus")
    }
}
