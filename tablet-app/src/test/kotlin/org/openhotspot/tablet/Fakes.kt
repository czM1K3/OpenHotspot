package org.openhotspot.tablet

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import org.openhotspot.common.Band
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.HotspotState
import org.openhotspot.common.PairingQr
import org.openhotspot.common.Security
import org.openhotspot.common.phone.ClientStore
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.HotspotController
import org.openhotspot.common.phone.HotspotException
import org.openhotspot.common.phone.InMemoryClientStore
import org.openhotspot.common.phone.PhoneEngine
import org.openhotspot.tablet.ble.BleException
import org.openhotspot.tablet.ble.BleLink
import org.openhotspot.tablet.ble.BleProblem
import org.openhotspot.tablet.ble.BleTransport
import org.openhotspot.tablet.ble.Sighting
import org.openhotspot.tablet.client.HotspotClient
import org.openhotspot.tablet.data.InMemoryPhoneStore
import org.openhotspot.tablet.data.PhoneRecord
import org.openhotspot.tablet.wifi.ProbeResult
import org.openhotspot.tablet.wifi.WifiJoinException
import org.openhotspot.tablet.wifi.WifiJoiner
import org.openhotspot.tablet.wifi.WifiLink
import org.openhotspot.tablet.wifi.WifiSpec

/** Time that follows a test scheduler, so delays, cooldowns and token windows are all virtual. */
class VirtualClock(private val baseWallMs: Long = 1_800_000_000_000L, private val now: () -> Long) : Clock {
    override fun wallMs() = baseWallMs + now()
    override fun monotonicMs() = now()
}

fun TestScope.virtualClock() = VirtualClock { testScheduler.currentTime }

/** A phone's hotspot as the real PhoneEngine sees it, with switches for the interesting situations. */
class FakeHotspot : HotspotController {
    var ready = true
    var active = false
    var failure: ErrorCode? = null
    /** enable() answers STARTING, then query() keeps answering STARTING this many times before ON. */
    var startingPolls = 0
    var neverOn = false
    var enableCalls = 0
    var disableCalls = 0
    var queryCalls = 0
    val on = HotspotInfo(HotspotState.ON, Security.WPA2_PSK, Band.GHZ_5, "Test Phone".toByteArray(), "correct horse battery")

    override fun isReady() = ready
    override fun isActive() = active

    override suspend fun enable(options: Int, autoOffSeconds: Int): HotspotInfo {
        failure?.let { throw HotspotException(it) }
        enableCalls++
        if (neverOn) return HotspotInfo(HotspotState.STARTING)
        if (startingPolls > 0) return HotspotInfo(HotspotState.STARTING)
        active = true
        return on
    }

    override suspend fun disable() { disableCalls++; active = false }

    override suspend fun query(): HotspotInfo {
        queryCalls++
        if (neverOn) return HotspotInfo(HotspotState.STARTING)
        if (startingPolls > 0) { startingPolls--; if (startingPolls > 0) return HotspotInfo(HotspotState.STARTING) }
        active = true
        return on
    }
}

/** A phone: the REAL protocol engine from :common, plus a Bluetooth address. */
class FakePhone(val clock: Clock, val address: String = "AA:BB:CC:DD:EE:01", var mtu: Int = 247) {
    val hotspot = FakeHotspot()
    val clients: ClientStore = InMemoryClientStore()
    val engine = PhoneEngine(clients, hotspot, clock)
    fun openPairing(): PairingQr = engine.openPairing()
}

/**
 * In-memory [BleTransport] in front of [FakePhone]s: chunks really go through the phone's reassembler
 * and the replies come back as real notifications, so chunking, framing, crypto and replay protection
 * are all exercised. A scan that finds nothing waits out its timeout (virtual time), like a real one.
 */
class FakeBleTransport : BleTransport {
    val inRange = mutableListOf<FakePhone>()
    var scans = 0
    val scanStartsMs = mutableListOf<Long>()
    var connects = 0
    /** The next N connect() calls fail with a retryable error. */
    var failConnects = 0
    /** The link drops (retryable DISCONNECTED) on the Nth write of the next connection. */
    var dropOnWrite: Int? = null
    var nowMs: () -> Long = { 0 }
    var tick = 0L

    override suspend fun <T : Any> scan(timeoutMs: Long, accept: (Sighting) -> T?): T? {
        scans++
        scanStartsMs += nowMs()
        for (p in inRange) {
            val slots = p.engine.advertisementSlots()
            for (i in 0 until slots) {
                val payload = p.engine.advertisementPayload(tick + i) ?: continue
                accept(Sighting(p.address, payload, -50))?.let { return it }
            }
        }
        delay(timeoutMs)
        return null
    }

    override suspend fun connect(address: String): BleLink {
        connects++
        if (failConnects > 0) { failConnects--; throw BleException(BleProblem.CONNECT_FAILED, "simulated", retryable = true) }
        val phone = inRange.firstOrNull { it.address == address } ?: throw BleException(BleProblem.CONNECT_FAILED, "gone", retryable = true)
        val drop = dropOnWrite
        dropOnWrite = null
        return FakeLink(phone.engine.openLink(phone.mtu), phone.mtu, drop)
    }
}

class FakeLink(private val phone: PhoneEngine.PhoneLink, override val mtu: Int, private val dropOnWrite: Int?) : BleLink {
    private val queue = ArrayDeque<ByteArray>()
    var writes = 0
    var closed = false

    override suspend fun write(chunk: ByteArray) {
        writes++
        if (dropOnWrite != null && writes == dropOnWrite) throw BleException(BleProblem.DISCONNECTED, "simulated drop", retryable = true)
        queue += phone.onWrite(chunk)
    }

    override suspend fun receive(timeoutMs: Long): ByteArray =
        queue.removeFirstOrNull() ?: throw BleException(BleProblem.TIMEOUT, "no notification")

    override fun close() { closed = true }
}

/** What a Wi-Fi join did, for assertions. */
class FakeWifi(var probes: MutableList<ProbeResult> = mutableListOf(ProbeResult.Online)) : WifiJoiner {
    var joins = 0
    var joinFailure: WifiJoinException? = null
    var lastSpec: WifiSpec? = null
    val links = mutableListOf<FakeWifiLink>()

    override suspend fun join(spec: WifiSpec, timeoutMs: Long): WifiLink {
        joins++
        lastSpec = spec
        joinFailure?.let { throw it }
        return FakeWifiLink(probes).also { links += it }
    }
}

class FakeWifiLink(private val probes: MutableList<ProbeResult>) : WifiLink {
    var probeCalls = 0
    var released = false
    private var lost: (() -> Unit)? = null

    override suspend fun probe(timeoutMs: Long): ProbeResult {
        probeCalls++
        return if (probes.size > 1) probes.removeAt(0) else probes.first()
    }

    override fun setLostListener(listener: (() -> Unit)?) { lost = listener }
    override fun release() { released = true }
    fun dropNetwork() { lost?.invoke() }
}

/** Pairs a [HotspotClient] with a fake phone and returns the stored record. */
suspend fun pairWith(client: HotspotClient, phone: FakePhone, label: String = "Test tablet"): PhoneRecord =
    client.pair(phone.openPairing(), label, "Test phone")

fun newStore() = InMemoryPhoneStore()
