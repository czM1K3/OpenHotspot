package org.openhotspot.phone.hotspot

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.IBinder
import java.lang.reflect.InvocationTargetException
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.openhotspot.common.Band
import org.openhotspot.common.Security
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Everything that must touch Shizuku's binder plumbing or hidden APIs lives behind this object.
 * NONE OF IT HAS RUN ON REAL HARDWARE YET (see docs/HOTSPOT_BACKENDS.md).
 */
object ShizukuBinders {
    @Volatile private var exemptionsTried = false
    @Volatile private var exemptionsOk = false

    /**
     * Android blocks reflection on hidden APIs for apps. The TetheringManager and IWifiManager
     * classes are hidden, and unlike the binder calls (which run inside the Shizuku server) the
     * reflection runs in OUR process, so we need an exemption. [HiddenApiBypass] (Apache-2.0)
     * adds one through the runtime. Returns whether the exemption call reported success.
     */
    @Synchronized
    fun ensureHiddenApiAccess(): Boolean {
        if (exemptionsTried) return exemptionsOk
        exemptionsTried = true
        exemptionsOk = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) true // no enforcement before Android 9
        else try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/net/", "Landroid/os/ServiceManager")
        } catch (e: Throwable) {
            false
        }
        return exemptionsOk
    }

    /** The binder of system service [name] (for example "wifi" or "tethering"), wrapped so calls run with Shizuku's identity. */
    fun wrapped(name: String): IBinder {
        val raw = SystemServiceHelper.getSystemService(name)
            ?: throw BackendException(FailureKind.API_UNAVAILABLE, "system service '$name' not found")
        return ShizukuBinderWrapper(raw)
    }

    /**
     * The package name the system services will accept for the Shizuku server's UID.
     * The tethering service rejects a caller package that does not belong to the calling UID
     * (`checkPackageNameMatchesUid`), and the calling UID is the Shizuku server's, not ours.
     * UID 2000 (adb) is `com.android.shell`; UID 0 (root) is the pseudo package `root`.
     */
    fun identityPackage(): String = if (Shizuku.getUid() == 0) "root" else "com.android.shell"

    /** Unwraps reflection failures: a SecurityException inside the callee is a permission problem, not a reflection problem. */
    fun <T> reflect(what: String, block: () -> T): T = try {
        block()
    } catch (e: BackendException) {
        throw e
    } catch (e: InvocationTargetException) {
        val cause = e.targetException ?: e
        if (cause is SecurityException) throw BackendException(FailureKind.PERMISSION_DENIED, "$what: ${cause.message}", cause)
        throw BackendException(FailureKind.START_FAILED, "$what threw ${cause.javaClass.simpleName}: ${cause.message}", cause)
    } catch (e: SecurityException) {
        throw BackendException(FailureKind.PERMISSION_DENIED, "$what: ${e.message}", e)
    } catch (e: ReflectiveOperationException) {
        throw BackendException(FailureKind.API_UNAVAILABLE, "$what: ${e.javaClass.simpleName}: ${e.message}", e)
    } catch (e: LinkageError) {
        throw BackendException(FailureKind.API_UNAVAILABLE, "$what: ${e.javaClass.simpleName}: ${e.message}", e)
    } catch (e: RuntimeException) {
        throw BackendException(FailureKind.API_UNAVAILABLE, "$what: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

/**
 * A Context that reports the Shizuku server's package as the op package. `TetheringManager` reads
 * only `getOpPackageName()` and `getAttributionTag()` from it (checked in AOSP android11..17).
 */
class ShellIdentityContext(base: Context, private val pkg: String) : ContextWrapper(base) {
    override fun getOpPackageName(): String = pkg
    override fun getAttributionTag(): String? = null
}

/** Result of reading the phone's saved hotspot configuration. */
sealed interface ConfigRead {
    data class Found(val credentials: HotspotCredentials) : ConfigRead
    data class Unavailable(val reason: String) : ConfigRead
}

/** Reads the hotspot state and saved configuration from the Wi-Fi service. */
interface WifiBinderApi {
    /** Blocking binder call. */
    fun readSoftApConfig(): ConfigRead

    /** `WifiManager.WIFI_AP_STATE_*` (13 = enabled), or null when it cannot be read. */
    fun apState(): Int?
}

/** Pure mapping from `SoftApConfiguration` fields to our credentials (unit-tested). */
object SoftApMapping {
    // SoftApConfiguration.SECURITY_TYPE_*
    const val SEC_OPEN = 0
    const val SEC_WPA2_PSK = 1
    const val SEC_WPA3_SAE_TRANSITION = 2
    const val SEC_WPA3_SAE = 3
    const val SEC_WPA3_OWE_TRANSITION = 4
    const val SEC_WPA3_OWE = 5

    // SoftApConfiguration.BAND_*
    const val BAND_2GHZ = 1
    const val BAND_5GHZ = 2
    const val BAND_6GHZ = 4

    const val WIFI_AP_STATE_ENABLED = 13

    fun security(type: Int): Security? = when (type) {
        SEC_WPA2_PSK -> Security.WPA2_PSK
        SEC_WPA3_SAE -> Security.WPA3_SAE
        SEC_WPA3_SAE_TRANSITION -> Security.WPA2_WPA3
        else -> null // open / OWE carry no passphrase, which the protocol (section 9) never produces
    }

    /** A single band maps to its own code; a multi-band mask means "unknown" (we cannot tell which one the AP chose). */
    fun band(mask: Int): Band = when (mask) {
        BAND_2GHZ -> Band.GHZ_2_4
        BAND_5GHZ -> Band.GHZ_5
        BAND_6GHZ -> Band.GHZ_6
        else -> Band.UNKNOWN
    }

    fun toCredentials(ssid: ByteArray?, passphrase: String?, securityType: Int, bandMask: Int): ConfigRead {
        if (ssid == null || ssid.isEmpty()) return ConfigRead.Unavailable("the phone has no saved hotspot SSID")
        val sec = security(securityType)
            ?: return ConfigRead.Unavailable("saved hotspot security type $securityType has no passphrase (open or OWE)")
        if (passphrase == null) return ConfigRead.Unavailable("the passphrase was not returned by the system")
        if (ssid.size > 32) return ConfigRead.Unavailable("saved SSID longer than 32 bytes")
        if (passphrase.toByteArray(Charsets.UTF_8).size !in 8..63) return ConfigRead.Unavailable("saved passphrase length is outside 8..63")
        return ConfigRead.Found(HotspotCredentials(ssid, passphrase, sec, band(bandMask), CredentialSource.SYSTEM))
    }
}

/**
 * Reads `IWifiManager.getSoftApConfiguration()` and `getWifiApEnabledState()` through the wrapped
 * "wifi" binder. Both AIDL methods take no arguments and exist unchanged in AOSP android11..17
 * (checked in IWifiManager.aidl of each branch). The shell UID holds NETWORK_SETTINGS, which the
 * service requires (WifiServiceImpl.getSoftApConfiguration).
 */
class ReflectiveWifiBinderApi : WifiBinderApi {
    private class Resolved(val service: Any, val getConfig: java.lang.reflect.Method, val getState: java.lang.reflect.Method)

    @Volatile private var resolved: Resolved? = null

    @Synchronized
    private fun resolve(): Resolved {
        resolved?.let { return it }
        return ShizukuBinders.reflect("resolve IWifiManager") {
            ShizukuBinders.ensureHiddenApiAccess()
            val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinders.wrapped("wifi"))
                ?: throw BackendException(FailureKind.API_UNAVAILABLE, "IWifiManager.Stub.asInterface returned null")
            val iface = Class.forName("android.net.wifi.IWifiManager")
            Resolved(service, iface.getMethod("getSoftApConfiguration"), iface.getMethod("getWifiApEnabledState")).also { resolved = it }
        }
    }

    override fun readSoftApConfig(): ConfigRead = try {
        val r = resolve()
        val cfg = ShizukuBinders.reflect("IWifiManager.getSoftApConfiguration") { r.getConfig.invoke(r.service) }
            ?: return ConfigRead.Unavailable("getSoftApConfiguration returned null")
        val cls = cfg.javaClass
        fun call(name: String): Any? = try { cls.getMethod(name).invoke(cfg) } catch (e: ReflectiveOperationException) { null }
        // Android 13+ exposes the SSID as WifiSsid (raw bytes); earlier versions only as a String.
        val ssid: ByteArray? = call("getWifiSsid")?.let { w -> try { w.javaClass.getMethod("getBytes").invoke(w) as? ByteArray } catch (e: ReflectiveOperationException) { null } }
            ?: (call("getSsid") as? String)?.toByteArray(Charsets.UTF_8)
        SoftApMapping.toCredentials(
            ssid, call("getPassphrase") as? String,
            (call("getSecurityType") as? Int) ?: -1, (call("getBand") as? Int) ?: 0,
        )
    } catch (e: BackendException) {
        ConfigRead.Unavailable("${e.kind}: ${e.message}")
    }

    override fun apState(): Int? = try {
        val r = resolve()
        ShizukuBinders.reflect("IWifiManager.getWifiApEnabledState") { r.getState.invoke(r.service) as? Int }
    } catch (e: BackendException) {
        null
    }
}
