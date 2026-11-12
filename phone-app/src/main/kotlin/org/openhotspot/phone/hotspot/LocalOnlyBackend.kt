package org.openhotspot.phone.hotspot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.Band
import org.openhotspot.common.Security
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel

/**
 * LAST RESORT. `WifiManager.startLocalOnlyHotspot` is a public API that needs no Shizuku, but the
 * network it creates is LOCAL ONLY: it has no internet sharing by design. A client that joins it can
 * talk to the phone and nothing else. The SSID and passphrase are chosen by the system, not by the user.
 *
 * Android 12 and older need ACCESS_FINE_LOCATION and Location turned on; Android 13+ needs NEARBY_WIFI_DEVICES.
 */
class LocalOnlyBackend(private val context: Context, private val log: EngineLog = EngineLog.NONE) : HotspotBackend {
    override val id = BackendId.LOCAL_ONLY
    override val sharesInternet = false
    override val credentialPolicy = CredentialPolicy.OWN

    private val wifi get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _active = MutableStateFlow<Boolean?>(false)
    override val active: StateFlow<Boolean?> get() = _active
    override val clients: StateFlow<Int?> = MutableStateFlow(null)

    override suspend fun probe(): ProbeResult {
        if (wifi == null) return ProbeResult.Unavailable(FailureKind.UNSUPPORTED, "no WifiManager")
        missingPermission()?.let { return ProbeResult.Unavailable(FailureKind.PERMISSION_DENIED, it) }
        return ProbeResult.Available("startLocalOnlyHotspot permissions present. WARNING: this hotspot has NO internet sharing")
    }

    private fun missingPermission(): String? {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33) {
            if (!granted(Manifest.permission.NEARBY_WIFI_DEVICES)) return "grant the 'Nearby devices' permission"
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return "grant the Location permission (needed by Android 12 and older)"
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !lm.isLocationEnabled) return "turn on Location (needed by Android 12 and older)"
        }
        return null
    }

    @SuppressLint("MissingPermission") // checked in missingPermission()
    override suspend fun start(credentials: HotspotCredentials?, timeoutMs: Long): HotspotCredentials? {
        val w = wifi ?: throw BackendException(FailureKind.UNSUPPORTED, "no WifiManager")
        missingPermission()?.let { throw BackendException(FailureKind.PERMISSION_DENIED, it) }
        reservation?.let { return credentialsOf(it) } // already running
        log.log(LogLevel.WARN, "Starting a LOCAL-ONLY hotspot: clients will NOT get internet")
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<WifiManager.LocalOnlyHotspotReservation>> { cont ->
                try {
                    w.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                            reservation = r
                            _active.value = true
                            if (cont.isActive) cont.resume(Result.success(r))
                        }

                        override fun onStopped() {
                            reservation = null
                            _active.value = false
                            log.log(LogLevel.INFO, "Local-only hotspot stopped by the system")
                        }

                        override fun onFailed(reason: Int) {
                            if (cont.isActive) cont.resume(Result.failure(BackendException(FailureKind.START_FAILED, "startLocalOnlyHotspot failed: ${reasonName(reason)}")))
                        }
                    }, main)
                } catch (e: SecurityException) {
                    cont.resume(Result.failure(BackendException(FailureKind.PERMISSION_DENIED, "startLocalOnlyHotspot: ${e.message}", e)))
                } catch (e: IllegalStateException) {
                    cont.resume(Result.failure(BackendException(FailureKind.START_FAILED, "startLocalOnlyHotspot: ${e.message}", e)))
                }
            }
        } ?: throw BackendException(FailureKind.TIMEOUT, "startLocalOnlyHotspot gave no result within $timeoutMs ms")
        return credentialsOf(outcome.getOrThrow())
    }

    private fun credentialsOf(r: WifiManager.LocalOnlyHotspotReservation): HotspotCredentials {
        val cfg: SoftApConfiguration = r.softApConfiguration
        val ssid = (if (Build.VERSION.SDK_INT >= 33) cfg.wifiSsid?.bytes else null) ?: cfg.ssid?.toByteArray(Charsets.UTF_8)
        val result = SoftApMapping.toCredentials(ssid, cfg.passphrase, cfg.securityType, 0)
        if (result !is ConfigRead.Found) {
            stopQuietly()
            throw BackendException(FailureKind.START_FAILED, "local-only hotspot started but its config is unusable: ${(result as ConfigRead.Unavailable).reason}")
        }
        return HotspotCredentials(result.credentials.ssid, result.credentials.passphrase, result.credentials.security, Band.UNKNOWN, CredentialSource.BACKEND)
    }

    override suspend fun stop() {
        stopQuietly()
    }

    private fun stopQuietly() {
        val r = reservation ?: return
        reservation = null
        try { r.close() } catch (e: Exception) { log.log(LogLevel.WARN, "closing the local-only reservation failed: ${e.javaClass.simpleName}") }
        _active.value = false
    }

    override fun close() = stopQuietly()

    private fun reasonName(r: Int) = when (r) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "NO_CHANNEL"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "GENERIC"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "INCOMPATIBLE_MODE"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "TETHERING_DISALLOWED"
        else -> "UNKNOWN($r)"
    }
}
