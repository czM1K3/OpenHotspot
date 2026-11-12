package org.openhotspot.tablet.client

import org.openhotspot.common.Advertisement
import org.openhotspot.common.Chunking
import org.openhotspot.common.ClientPairing
import org.openhotspot.common.EnableBody
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.Frame
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.MsgType
import org.openhotspot.common.PairingQr
import org.openhotspot.common.Reassembler
import org.openhotspot.common.Session
import org.openhotspot.common.crypto.RandomSource
import org.openhotspot.common.crypto.SystemRandom
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.SystemClock
import org.openhotspot.common.readU16
import org.openhotspot.tablet.ble.BleException
import org.openhotspot.tablet.ble.BleLink
import org.openhotspot.tablet.ble.BleProblem
import org.openhotspot.tablet.ble.BleTransport
import org.openhotspot.tablet.data.PhoneRecord
import org.openhotspot.tablet.data.PhoneStore

/** A paired phone whose advertisement we just recognised. */
class FoundPhone(val phone: PhoneRecord, val address: String, val rssi: Int)

/** Finds a paired phone by its advertisement. An interface so the auto-trigger can be tested without Bluetooth. */
fun interface PhoneScanner {
    suspend fun findPhone(phones: List<PhoneRecord>, timeoutMs: Long): FoundPhone?
}

/**
 * The client side of docs/PROTOCOL.md on top of a [BleTransport]: recognising the phone, pairing,
 * and encrypted requests with chunking and reassembly. The cryptography and framing come from
 * `:common`; this class only moves frames and keeps counters.
 */
class HotspotClient(
    private val transport: BleTransport,
    private val store: PhoneStore,
    private val clock: Clock = SystemClock,
    private val random: RandomSource = SystemRandom,
    private val log: (String) -> Unit = {},
) : PhoneScanner {
    /** Scans for any of [phones] (paired-mode token, windows w-1..w+1). Null if none shows up in [timeoutMs]. */
    override suspend fun findPhone(phones: List<PhoneRecord>, timeoutMs: Long): FoundPhone? {
        if (phones.isEmpty()) return null
        return transport.scan(timeoutMs) { s ->
            val now = clock.wallMs() / 1000
            phones.firstOrNull { Advertisement.matchesPaired(s.serviceData, it.keys.kAdv, now) }
                ?.let { FoundPhone(it, s.address, s.rssi) }
        }
    }

    /** Opens a connection to a recognised phone. */
    suspend fun open(found: FoundPhone): PhoneSession = PhoneSession(found.phone, transport.connect(found.address), store, clock, random)

    /**
     * Pairs using the scanned QR. The phone advertises a pairing token derived from the QR's secret
     * while its pairing screen is open; only that phone matches.
     */
    suspend fun pair(qr: PairingQr, ourLabel: String, phoneName: String, scanTimeoutMs: Long = 20_000): PhoneRecord {
        val address = transport.scan(scanTimeoutMs) { s ->
            s.address.takeIf { Advertisement.matchesPairing(s.serviceData, qr.secret, clock.wallMs() / 1000) }
        } ?: throw BleException(BleProblem.TIMEOUT, "The phone was not found. Keep its pairing screen open and close to the tablet.", retryable = true)
        val pairing = ClientPairing(qr, ourLabel, clock::wallMs, random)
        val link = transport.connect(address)
        val paired = try {
            val reply = exchange(link, pairing.requestFrame(), RESPONSE_TIMEOUT_MS)
            pairing.finish(reply)
        } finally {
            link.close()
        }
        val record = PhoneRecord(paired.phoneId, paired.ltk, paired.phonePub, ourLabel, phoneName, clock.wallMs())
        store.add(record)
        log("Paired with phone ${paired.phoneId}")
        return record
    }

    companion object {
        /** The phone answers within 20 s (PROTOCOL.md section 7); a little slack for BLE. */
        const val RESPONSE_TIMEOUT_MS = 25_000L

        /** Sends [frame] in chunks and returns the reassembled reply frame. */
        suspend fun exchange(link: BleLink, frame: Frame, timeoutMs: Long, clock: Clock = SystemClock): Frame {
            for (chunk in Chunking.chunkFrame(frame.encode(), link.mtu)) link.write(chunk)
            val reassembler = Reassembler(clock::monotonicMs)
            val deadline = clock.monotonicMs() + timeoutMs
            while (true) {
                val remaining = deadline - clock.monotonicMs()
                if (remaining <= 0) throw BleException(BleProblem.TIMEOUT, "The phone did not answer in time", retryable = false)
                val chunk = link.receive(remaining)
                val raw = reassembler.feed(chunk) ?: continue
                return Frame.decode(raw)
            }
        }
    }
}

/**
 * One connection to one phone. Requests are strictly sequential (the phone answers BUSY otherwise).
 * Every request takes a fresh counter that is persisted before the request is sent.
 */
class PhoneSession internal constructor(
    val phone: PhoneRecord,
    private val link: BleLink,
    private val store: PhoneStore,
    private val clock: Clock,
    private val random: RandomSource,
) : AutoCloseable {
    val mtu: Int get() = link.mtu

    private suspend fun request(type: MsgType, body: ByteArray, timeoutMs: Long = HotspotClient.RESPONSE_TIMEOUT_MS): ByteArray {
        val counter = store.nextCounter(phone.phoneId) // durable before the bytes leave (PROTOCOL.md section 6)
        val msgId = random.bytes(2).readU16(0)
        val built = Session.buildRequest(phone.keys.kReq, type, msgId, clock.wallMs(), counter, body, random = random)
        val reply = HotspotClient.exchange(link, built.frame, timeoutMs, clock)
        return Session.parseResponse(phone.keys.kResp, reply, type, msgId, built.nonce, counter, clock.wallMs())
    }

    /** Turns the hotspot on. May return STARTING; call [query] until ON. [autoOffSeconds] 0 means the phone's default. */
    suspend fun enable(autoOffSeconds: Int = 0): HotspotInfo = HotspotInfo.decode(request(MsgType.ENABLE, EnableBody.pack(0, autoOffSeconds)))

    suspend fun query(): HotspotInfo = HotspotInfo.decode(request(MsgType.QUERY, ByteArray(0)))

    suspend fun disable() {
        request(MsgType.DISABLE, ByteArray(0)) // body is the state byte; nothing in it changes what we do
    }

    /** Asks the phone to forget this tablet. The caller removes the local record regardless. */
    suspend fun unpair() {
        request(MsgType.UNPAIR, ByteArray(0))
    }

    override fun close() = link.close()
}

/** True when the failure means "start over with a fresh scan and connection" (DECISIONS.md D33, same rule as the Linux client). */
fun Throwable.isRetryableLinkFailure(): Boolean = this is BleException && retryable

/** The phone's own error code, if the failure was an ERROR frame. */
val Throwable.phoneErrorCode: ErrorCode? get() = (this as? org.openhotspot.common.PhoneException)?.code
