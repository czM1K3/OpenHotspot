package org.openhotspot.tablet.flow

import kotlinx.coroutines.delay
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.HotspotState
import org.openhotspot.common.Security
import org.openhotspot.tablet.client.FoundPhone
import org.openhotspot.tablet.client.HotspotClient
import org.openhotspot.tablet.client.HotspotNotReadyException
import org.openhotspot.tablet.client.PhoneNotFoundException
import org.openhotspot.tablet.client.isRetryableLinkFailure
import org.openhotspot.tablet.data.AppSettings
import org.openhotspot.tablet.data.JoinMethod
import org.openhotspot.tablet.data.PhoneRecord
import org.openhotspot.tablet.wifi.ProbeResult
import org.openhotspot.tablet.wifi.WifiJoiner
import org.openhotspot.tablet.wifi.WifiLink
import org.openhotspot.tablet.wifi.WifiSpec

enum class Stage { SCANNING, CONNECTING, REQUESTING, STARTING_HOTSPOT, JOINING, VERIFYING }

/** The result of a successful join. [link] must be released to leave the network. */
class Connected(val phone: PhoneRecord, val link: WifiLink, val internet: ProbeResult)

/** Joined, but the internet check failed. The link is handed over so the caller decides: keep or undo. */
class NoInternetException(val connected: Connected) : Exception("joined, but no internet (${connected.internet})")

/**
 * enable the hotspot over BLE, join it, verify the internet. One sequence for the button, the tile and
 * the auto-trigger (the same single-flow idea as `connect_flow` in the Linux client, DECISIONS.md D34).
 *
 * Retries: a dropped BLE link restarts the whole request with a fresh scan, connection and counter
 * (never the same counter), up to [attempts] times. Errors the phone itself reports are not retried.
 */
class ConnectFlow(
    private val client: HotspotClient,
    private val wifi: (JoinMethod) -> WifiJoiner,
    private val timing: Timing = Timing(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    data class Timing(
        val scanMs: Long = 15_000,
        val attempts: Int = 2,
        /** How long to wait for the hotspot to reach ON after ENABLE returned STARTING. */
        val hotspotReadyMs: Long = 20_000,
        val queryIntervalMs: Long = 1_000,
        /** The system prompt waits for the user, so this is generous. */
        val joinMs: Long = 60_000,
        val verifyMs: Long = 20_000,
        val probeEveryMs: Long = 1_500,
        val probeTimeoutMs: Long = 4_000,
    )

    /**
     * [phoneId] limits the scan to one phone; null accepts any paired phone. Throws:
     * [PhoneNotFoundException], BLE/protocol/phone exceptions, [HotspotNotReadyException],
     * `WifiJoinException`, or [NoInternetException] (joined, but unverified; the caller owns the link).
     */
    suspend fun run(
        phones: List<PhoneRecord>,
        phoneId: String?,
        settings: AppSettings,
        progress: (Stage) -> Unit,
    ): Connected {
        val candidates = phones.filter { phoneId == null || it.phoneId == phoneId }
        if (candidates.isEmpty()) throw PhoneNotFoundException()
        val (found, info) = enableWithRetry(candidates, progress)

        val spec = WifiSpec(info.ssid, info.passphrase, info.security, settings.hiddenSsid)
        progress(Stage.JOINING)
        val link = wifi(settings.joinMethod).join(spec, timing.joinMs)
        try {
            progress(Stage.VERIFYING)
            val result = verify(link)
            val connected = Connected(found.phone, link, result)
            if (result != ProbeResult.Online) throw NoInternetException(connected)
            return connected
        } catch (e: NoInternetException) {
            throw e
        } catch (e: Throwable) { // cancelled or failed while verifying: do not leave a half-joined network behind
            link.release()
            throw e
        }
    }

    private suspend fun enableWithRetry(candidates: List<PhoneRecord>, progress: (Stage) -> Unit): Pair<FoundPhone, HotspotInfo> {
        var attempt = 0
        while (true) {
            attempt++
            try {
                progress(Stage.SCANNING)
                val found = client.findPhone(candidates, timing.scanMs) ?: throw PhoneNotFoundException()
                progress(Stage.CONNECTING)
                client.open(found).use { session ->
                    progress(Stage.REQUESTING)
                    var info = session.enable()
                    if (info.state != HotspotState.ON) {
                        progress(Stage.STARTING_HOTSPOT)
                        var waited = 0L
                        while (info.state != HotspotState.ON && waited < timing.hotspotReadyMs) {
                            sleep(timing.queryIntervalMs)
                            waited += timing.queryIntervalMs
                            info = session.query()
                        }
                    }
                    if (info.state != HotspotState.ON) throw HotspotNotReadyException("The hotspot did not come on within ${timing.hotspotReadyMs / 1000} s.")
                    if (info.ssid.isEmpty() || (info.security != Security.OPEN && info.passphrase.isEmpty())) {
                        throw HotspotNotReadyException("The phone did not send the hotspot name and password.")
                    }
                    return found to info
                }
            } catch (e: Exception) {
                if (e.isRetryableLinkFailure() && attempt < timing.attempts) {
                    log("Bluetooth attempt $attempt failed (${e.javaClass.simpleName}); retrying with a new connection")
                    continue
                }
                throw e
            }
        }
    }

    /** Probes until the internet answers or [Timing.verifyMs] is used up. The last result tells why it did not. */
    private suspend fun verify(link: WifiLink): ProbeResult {
        val rounds = (timing.verifyMs / timing.probeEveryMs).toInt().coerceAtLeast(1)
        var last: ProbeResult = link.probe(timing.probeTimeoutMs)
        var i = 1
        while (last != ProbeResult.Online && i < rounds) {
            sleep(timing.probeEveryMs)
            last = link.probe(timing.probeTimeoutMs)
            i++
        }
        return last
    }
}
