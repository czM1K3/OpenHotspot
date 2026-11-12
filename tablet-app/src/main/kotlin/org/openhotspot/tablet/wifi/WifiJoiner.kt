package org.openhotspot.tablet.wifi

import org.openhotspot.common.Security

/** Everything needed to join. The [passphrase] is secret: never log or format this class. */
class WifiSpec(
    val ssid: ByteArray,
    val passphrase: String,
    val security: Security,
    val hidden: Boolean,
) {
    override fun toString() = "WifiSpec(<redacted>)"
}

enum class WifiProblem {
    /** `onUnavailable`: the user declined the system prompt, or the network was not found in time. Android does not say which. */
    NOT_AVAILABLE,
    WIFI_OFF,
    /** The OS refused the request or suggestion outright (permission, admin restriction, duplicate). */
    REJECTED,
    UNSUPPORTED_SSID,
    UNSUPPORTED_SECURITY,
    TIMEOUT,
    LOST,
}

class WifiJoinException(val problem: WifiProblem, message: String? = null) : Exception(message ?: problem.name)

/** What an internet check found. */
sealed interface ProbeResult {
    data object Online : ProbeResult
    /** Answered, but not with the expected "no content": a login page or a redirect. */
    data object CaptivePortal : ProbeResult
    data class Offline(val reason: String) : ProbeResult
}

/** A joined network. Release it to leave the network and undo any process binding. */
interface WifiLink {
    /** One HTTP request over exactly this network (not the system default). Never throws. */
    suspend fun probe(timeoutMs: Long): ProbeResult

    /** Called once if the system drops the network on its own. */
    fun setLostListener(listener: (() -> Unit)?)

    fun release()
}

/** The Wi-Fi layer. Implementations: [SpecifierJoiner] and [SuggestionJoiner]. */
interface WifiJoiner {
    /**
     * Joins and returns once the link layer is up. Throws [WifiJoinException]. Cancelling the
     * coroutine abandons the request cleanly (callbacks unregistered, suggestions removed).
     */
    suspend fun join(spec: WifiSpec, timeoutMs: Long): WifiLink
}
