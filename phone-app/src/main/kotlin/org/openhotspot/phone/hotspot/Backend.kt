package org.openhotspot.phone.hotspot

import kotlinx.coroutines.flow.StateFlow
import org.openhotspot.common.Band
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.HotspotState
import org.openhotspot.common.Security

/** The hotspot implementations, in the order they are tried. */
enum class BackendId(val label: String) {
    SHIZUKU_TETHERING("Shizuku + TetheringManager"),
    SHELL_COMMAND("Shizuku + shell command (cmd wifi)"),
    LOCAL_ONLY("Local-only hotspot (NO internet sharing)"),
}

/** Why a backend is unusable or failed. Each kind maps to the protocol error the client sees. */
enum class FailureKind(val errorCode: ErrorCode) {
    SHIZUKU_NOT_RUNNING(ErrorCode.SHIZUKU_UNAVAILABLE),
    SHIZUKU_NOT_AUTHORIZED(ErrorCode.PERMISSION_DENIED),
    SHIZUKU_TOO_OLD(ErrorCode.SHIZUKU_UNAVAILABLE),

    /** Hidden API missing, renamed or blocked by the OS (reflection failed). */
    API_UNAVAILABLE(ErrorCode.HOTSPOT_FAILED),

    /** The OS refused the call (SecurityException, tethering error 14/15, missing runtime permission). */
    PERMISSION_DENIED(ErrorCode.PERMISSION_DENIED),
    NO_CREDENTIALS(ErrorCode.HOTSPOT_FAILED),
    START_FAILED(ErrorCode.HOTSPOT_FAILED),
    TIMEOUT(ErrorCode.HOTSPOT_FAILED),
    UNSUPPORTED(ErrorCode.HOTSPOT_FAILED),
    DISABLED_BY_SETTING(ErrorCode.HOTSPOT_FAILED),

    /** Anything not classified above; treat as a backend bug. */
    INTERNAL(ErrorCode.HOTSPOT_FAILED),
    ;

    /** Whether the next backend may be tried after this failure. Missing credentials must not fall through to a worse backend. */
    val allowsFallback: Boolean get() = this != NO_CREDENTIALS
}

class BackendException(val kind: FailureKind, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface ProbeResult {
    /** [detail] is a short, human-readable, secret-free description of what was verified. */
    data class Available(val detail: String) : ProbeResult
    data class Unavailable(val kind: FailureKind, val reason: String) : ProbeResult
}

/** Where the credentials we report to the client came from. */
enum class CredentialSource { SYSTEM, USER, BACKEND }

/** Which source to prefer when both the phone's saved hotspot config and the user's entry are available. */
enum class CredentialPolicy {
    /** The OS starts the AP from its saved config, so what we report must be what the OS saved. */
    SYSTEM_FIRST,

    /** We pass the credentials to the OS, so an explicit user entry wins. */
    USER_FIRST,

    /** The backend produces its own credentials (local-only hotspot). */
    OWN,
}

/** SSID and passphrase. [toString] hides both so they cannot reach a log by accident. */
class HotspotCredentials(
    val ssid: ByteArray,
    val passphrase: String,
    val security: Security = Security.WPA2_PSK,
    val band: Band = Band.UNKNOWN,
    val source: CredentialSource = CredentialSource.USER,
) {
    fun toInfo(state: HotspotState = HotspotState.ON) = HotspotInfo(state, security, band, ssid, passphrase)

    fun withSource(s: CredentialSource) = HotspotCredentials(ssid, passphrase, security, band, s)

    override fun toString() = "HotspotCredentials($security, $band, source=$source, ssid=<hidden>, passphrase=<hidden>)"
}

/** Validation shared by the settings UI and the backends. Returns null when valid, else a message. */
object CredentialRules {
    fun validate(ssid: String, passphrase: String): String? {
        val s = ssid.toByteArray(Charsets.UTF_8)
        if (s.isEmpty()) return "SSID must not be empty"
        if (s.size > 32) return "SSID must be at most 32 bytes (UTF-8)"
        val p = passphrase.toByteArray(Charsets.UTF_8)
        if (p.size !in 8..63) return "Passphrase must be 8 to 63 bytes"
        if (passphrase.any { it.isISOControl() }) return "Passphrase contains a control character"
        return null
    }
}

/**
 * One way of turning the hotspot on and off. Everything Android-specific hides behind this so the
 * selection and fallback logic is plain JVM code under test.
 */
interface HotspotBackend {
    val id: BackendId

    /** Whether, when it works, this backend gives clients internet. False only for the local-only fallback. */
    val sharesInternet: Boolean
    val credentialPolicy: CredentialPolicy

    /** True/false once known; null while unknown. */
    val active: StateFlow<Boolean?>

    /** Connected clients, or null when this backend cannot count them (auto-off is then disabled). */
    val clients: StateFlow<Int?>

    /** Cheap, non-invasive check (never toggles the hotspot). Must not throw. */
    suspend fun probe(): ProbeResult

    /**
     * Turns the hotspot on and returns once it is up. [credentials] is what the caller will report
     * (or, for [CredentialPolicy.USER_FIRST] backends, what to configure). Returns the backend's own
     * credentials when the policy is [CredentialPolicy.OWN], else null.
     * @throws BackendException on failure.
     */
    suspend fun start(credentials: HotspotCredentials?, timeoutMs: Long): HotspotCredentials?

    /** @throws BackendException on failure. Stopping an already stopped hotspot is not an error. */
    suspend fun stop()

    fun close() {}
}

/** Maps TetheringManager.TETHER_ERROR_* (values verified against the Android 16 SDK stubs). */
object TetherErrors {
    fun name(code: Int): String = when (code) {
        0 -> "NO_ERROR"
        1 -> "UNKNOWN_IFACE"
        2 -> "SERVICE_UNAVAIL"
        3 -> "UNSUPPORTED"
        4 -> "UNAVAIL_IFACE"
        5 -> "INTERNAL_ERROR"
        6 -> "TETHER_IFACE_ERROR"
        7 -> "UNTETHER_IFACE_ERROR"
        8 -> "ENABLE_FORWARDING_ERROR"
        9 -> "DISABLE_FORWARDING_ERROR"
        10 -> "IFACE_CFG_ERROR"
        11 -> "PROVISIONING_FAILED"
        12 -> "DHCPSERVER_ERROR"
        13 -> "ENTITLEMENT_UNKNOWN"
        14 -> "NO_CHANGE_TETHERING_PERMISSION"
        15 -> "NO_ACCESS_TETHERING_PERMISSION"
        16 -> "UNKNOWN_TYPE"
        17 -> "UNKNOWN_REQUEST"
        18 -> "DUPLICATE_REQUEST"
        else -> "UNKNOWN($code)"
    }

    fun kind(code: Int): FailureKind = when (code) {
        14, 15 -> FailureKind.PERMISSION_DENIED
        3, 16 -> FailureKind.UNSUPPORTED
        else -> FailureKind.START_FAILED
    }

    const val DUPLICATE_REQUEST = 18
}
