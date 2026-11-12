package org.openhotspot.tablet.client

import org.openhotspot.common.AuthenticationException
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.PairingException
import org.openhotspot.common.PhoneException
import org.openhotspot.common.ProtocolException
import org.openhotspot.tablet.ble.BleException
import org.openhotspot.tablet.ble.BleProblem
import org.openhotspot.tablet.wifi.WifiJoinException
import org.openhotspot.tablet.wifi.WifiProblem

/** What the UI shows: a one-line [title] and what to try. Never contains keys, SSIDs or passphrases. */
data class UserError(val title: String, val hint: String? = null)

/** The connect flow found no paired phone in range. */
class PhoneNotFoundException : Exception("No paired phone found nearby")

/** The phone's hotspot did not reach ON in time or came back without credentials. */
class HotspotNotReadyException(message: String) : Exception(message)

/** Translates every failure the layers can raise. Unknown exceptions get a generic message, not a stack trace. */
object ErrorText {
    fun describe(e: Throwable): UserError = when (e) {
        is PhoneNotFoundException -> UserError(
            "Phone not found nearby",
            "Keep the phone close, with Bluetooth on and the OpenHotspot service running (see SETUP.md for battery settings).",
        )
        is BleException -> ble(e)
        is PhoneException -> phone(e)
        is WifiJoinException -> wifi(e)
        is HotspotNotReadyException -> UserError("The phone's hotspot did not start", e.message)
        is AuthenticationException -> UserError(
            "The phone's answer could not be verified",
            "The pairing may be out of date. If it keeps failing, pair again.",
        )
        is PairingException -> UserError("Pairing failed", e.message)
        is ProtocolException -> when (e.code) {
            ErrorCode.STALE_TIMESTAMP -> UserError("Clocks differ by more than a minute", "Turn on automatic date and time on both devices.")
            else -> UserError("Unexpected data from the phone", e.message)
        }
        is kotlinx.coroutines.TimeoutCancellationException -> UserError("Timed out")
        else -> UserError("Something went wrong (${e.javaClass.simpleName})", e.message?.take(120))
    }

    private fun ble(e: BleException) = when (e.problem) {
        BleProblem.BLUETOOTH_OFF -> UserError("Bluetooth is off", "Turn Bluetooth on and try again.")
        BleProblem.PERMISSION -> UserError("Bluetooth permission missing", e.message)
        BleProblem.SCAN_FAILED -> UserError("Could not scan for the phone", e.message)
        BleProblem.CONNECT_FAILED -> UserError("Could not connect to the phone", "Move closer and try again. ${e.message.orEmpty()}".trim())
        BleProblem.SERVICE_MISSING -> UserError("That device does not offer OpenHotspot", "Check that the phone's OpenHotspot service is running.")
        BleProblem.DISCONNECTED -> UserError("The Bluetooth link to the phone dropped", "Try again, closer to the phone.")
        BleProblem.TIMEOUT -> UserError(e.message ?: "The phone did not answer in time", "Try again, closer to the phone.")
    }

    /** Phone-reported errors (PROTOCOL.md section 5). An unauthenticated one is only a hint and says so. */
    private fun phone(e: PhoneException): UserError {
        val hintOnly = !e.authenticated
        return when (e.code) {
            ErrorCode.AUTH_FAILED -> UserError(
                "The phone did not accept this tablet",
                "The pairing may have been removed on the phone. Pair again if it persists." + if (hintOnly) " (unverified answer; the pairing was kept)" else "",
            )
            ErrorCode.RATE_LIMITED -> UserError("The phone is temporarily locked", "Wait ${e.retryAfterSeconds.coerceAtLeast(1)} s and try again.")
            ErrorCode.REPLAY -> UserError("The phone rejected the request counter", "Try again. If it persists, unpair and pair again.")
            ErrorCode.STALE_TIMESTAMP -> UserError("Clocks differ by more than a minute", "Turn on automatic date and time on both devices.")
            ErrorCode.BUSY -> UserError("The phone is busy", "Try again in a moment.")
            ErrorCode.SHIZUKU_UNAVAILABLE -> UserError("Shizuku is not running on the phone", "Start Shizuku on the phone (it stops after a reboot).")
            ErrorCode.PERMISSION_DENIED -> UserError("Shizuku permission is missing on the phone", "Grant OpenHotspot access in the Shizuku app.")
            ErrorCode.HOTSPOT_FAILED -> UserError("The phone could not start its hotspot", "Check the phone's mobile data and hotspot settings.")
            ErrorCode.NOT_IN_PAIRING_MODE -> UserError("The phone's pairing screen is not open", "Open the pairing screen on the phone and scan the new QR code.")
            ErrorCode.TOO_MANY_CLIENTS -> UserError("The phone has reached its limit of 8 paired devices", "Remove one in the phone app.")
            ErrorCode.MALFORMED, ErrorCode.UNSUPPORTED_VERSION, ErrorCode.UNSUPPORTED_REQUEST ->
                UserError("The phone could not understand the request", "The apps may be different versions.")
            ErrorCode.INTERNAL, null -> UserError("The phone reported an internal error", "Code 0x${e.rawCode.toString(16)}")
        }
    }

    private fun wifi(e: WifiJoinException) = when (e.problem) {
        WifiProblem.NOT_AVAILABLE -> UserError(
            "Could not join the hotspot",
            "The request was declined, or the network was not found. Approve the system prompt, and if the hotspot is hidden turn on \"Hidden network\" in Settings.",
        )
        WifiProblem.WIFI_OFF -> UserError("Wi-Fi is off", "Turn Wi-Fi on and try again.")
        WifiProblem.REJECTED -> UserError("Android refused the Wi-Fi request", e.message)
        WifiProblem.UNSUPPORTED_SSID -> UserError("The hotspot name cannot be used on this Android version", e.message)
        WifiProblem.UNSUPPORTED_SECURITY -> UserError("Unsupported hotspot security", e.message)
        WifiProblem.TIMEOUT -> UserError("Timed out joining the hotspot", "Move closer, and check the band (SETUP.md).")
        WifiProblem.LOST -> UserError("The hotspot connection was lost")
    }

    val noInternet = UserError(
        "Joined the hotspot, but the internet is not reachable",
        "The phone may have no mobile data, or it fell back to its local-only hotspot, or a captive portal intercepts.",
    )
}
