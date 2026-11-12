package org.openhotspot.common.phone

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.openhotspot.common.Advertisement
import org.openhotspot.common.AuthenticationException
import org.openhotspot.common.Chunking
import org.openhotspot.common.EnableBody
import org.openhotspot.common.Envelope
import org.openhotspot.common.ErrorBodies
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.Frame
import org.openhotspot.common.HotspotState
import org.openhotspot.common.Keys
import org.openhotspot.common.MsgType
import org.openhotspot.common.PairingQr
import org.openhotspot.common.Proto
import org.openhotspot.common.ProtocolException
import org.openhotspot.common.Reassembler
import org.openhotspot.common.SealedBody
import org.openhotspot.common.Status
import org.openhotspot.common.crypto.Crypto
import org.openhotspot.common.crypto.RandomSource
import org.openhotspot.common.crypto.SystemRandom
import org.openhotspot.common.readU16
import org.openhotspot.common.readU64
import org.openhotspot.common.toHex
import org.openhotspot.common.u64

/**
 * The phone side of docs/PROTOCOL.md: pairing window, trial decryption, replay and timestamp
 * checks, rate limiting, request dispatch and advertisement payloads. Pure JVM: Bluetooth and the
 * hotspot are reached only through [HotspotController] and a transport that calls [PhoneLink.onWrite].
 */
class PhoneEngine(
    private val store: ClientStore,
    private val controller: HotspotController,
    private val clock: Clock = SystemClock,
    private val random: RandomSource = SystemRandom,
    private val log: EngineLog = EngineLog.NONE,
    private val limiter: RateLimiter = RateLimiter(clock),
) {
    private class PairingSession(val priv: ByteArray, val pub: ByteArray, val secret: ByteArray, val expiresMs: Long) {
        var failures = 0
    }

    private val lock = Any()
    private var pairing: PairingSession? = null

    // ---- user actions ---------------------------------------------------------------------

    /** Starts a pairing window (fresh keypair and secret) and returns the QR to display. */
    fun openPairing(): PairingQr = synchronized(lock) {
        val priv = Crypto.generatePrivateKey(random)
        val session = PairingSession(priv, Crypto.publicKey(priv), random.bytes(16), clock.wallMs() + Proto.PAIRING_WINDOW_MS)
        pairing = session
        log.log(LogLevel.INFO, "Pairing window opened (${Proto.PAIRING_WINDOW_MS / 1000} s)")
        PairingQr(Proto.VERSION, Proto.SERVICE_UUID_BYTES, session.pub, session.secret)
    }

    fun closePairing() = synchronized(lock) {
        if (pairing != null) log.log(LogLevel.INFO, "Pairing window closed")
        pairing = null
    }

    fun isPairingOpen(): Boolean = synchronized(lock) { currentPairing() != null }

    /** Milliseconds left in the pairing window, or 0. */
    fun pairingRemainingMs(): Long = synchronized(lock) { currentPairing()?.let { it.expiresMs - clock.wallMs() } ?: 0L }

    fun clients(): List<ClientRecord> = store.list()

    fun revoke(id: String): Boolean {
        val label = store.list().firstOrNull { it.id == id }?.label
        val removed = store.remove(id)
        if (removed) log.log(LogLevel.INFO, "Revoked client '${label}'")
        return removed
    }

    fun status(): Status = Status(Proto.VERSION, controller.isReady(), isPairingOpen(), controller.isActive())

    fun lockoutSeconds(): Int = limiter.retryAfterSeconds()

    private fun currentPairing(): PairingSession? {
        val p = pairing
        if (p != null && clock.wallMs() > p.expiresMs) {
            pairing = null
            log.log(LogLevel.INFO, "Pairing window expired")
        }
        return pairing
    }

    // ---- advertisement --------------------------------------------------------------------

    /** Number of distinct tokens the advertiser should rotate through (0 = nothing to advertise). */
    fun advertisementSlots(): Int = synchronized(lock) { if (currentPairing() != null) 1 else store.list().size }

    /**
     * The 9-byte service-data payload for rotation step [tick]. In pairing mode only the pairing
     * token is advertised; otherwise the per-client tokens are cycled. Null when there is nothing
     * to advertise (no pairing window and no paired clients).
     */
    fun advertisementPayload(tick: Long): ByteArray? = synchronized(lock) {
        val nowS = clock.wallMs() / 1000
        currentPairing()?.let { return Advertisement.buildPayload(Proto.ADV_MODE_PAIRING, Advertisement.pairingToken(it.secret, nowS)) }
        val clients = store.list()
        if (clients.isEmpty()) return null
        val c = clients[Math.floorMod(tick, clients.size.toLong()).toInt()]
        Advertisement.buildPayload(Proto.ADV_MODE_PAIRED, Advertisement.pairedToken(c.keys.kAdv, nowS))
    }

    // ---- links ----------------------------------------------------------------------------

    fun openLink(mtu: Int = Proto.MIN_MTU): PhoneLink = PhoneLink(this, mtu)

    /** One BLE connection as seen by the phone. Feed it every Request write with [onWrite]. */
    class PhoneLink internal constructor(private val engine: PhoneEngine, @Volatile var mtu: Int) {
        private val reassembler = Reassembler(engine.clock::monotonicMs)
        internal var failures = 0
        internal val busy = AtomicBoolean(false)

        /** True once a request on this link has authenticated. */
        @Volatile var authenticated = false
            internal set

        /** Set when the link has misbehaved (3 failures); the transport should disconnect it. */
        @Volatile var closeRequested = false
            internal set

        /** Handles one Request write and returns the Response notifications to send, in order. */
        suspend fun onWrite(chunk: ByteArray): List<ByteArray> {
            val raw = try {
                reassembler.feed(chunk)
            } catch (e: ProtocolException) {
                return engine.reply(this, engine.plainError(0, e.code), countFailure = true)
            } ?: return emptyList()
            return engine.handle(this, raw)
        }
    }

    // ---- framing helpers ------------------------------------------------------------------

    private fun plainError(msgId: Int, code: ErrorCode, retry: Int = 0) =
        Frame(MsgType.ERROR, msgId, ErrorBodies.packPlain(code.code, retry), Proto.FLAG_PLAINTEXT)

    private fun authError(key: ByteArray, msgId: Int, reqNonce: ByteArray, reqCounter: Long, code: ErrorCode): Frame {
        val header = Frame(MsgType.ERROR, msgId, ByteArray(0)).header
        val plaintext = ErrorBodies.packAuth(clock.wallMs(), reqCounter, code.code)
        return Frame(MsgType.ERROR, msgId, SealedBody.seal(key, header + reqNonce, plaintext, random = random))
    }

    internal fun reply(link: PhoneLink, frame: Frame, countFailure: Boolean = false): List<ByteArray> {
        if (countFailure) {
            limiter.recordFailure()
            link.failures++
            if (limiter.retryAfterSeconds() > 0) log.log(LogLevel.WARN, "Lockout for ${limiter.retryAfterSeconds()} s after repeated failures")
        }
        if (link.failures >= RateLimiter.CONNECTION_MAX_FAILURES) link.closeRequested = true
        return Chunking.chunkFrame(frame.encode(), link.mtu)
    }

    private fun skewed(timestampMs: Long) = abs(timestampMs - clock.wallMs()) > Proto.TIMESTAMP_SKEW_MS

    // ---- dispatch -------------------------------------------------------------------------

    internal suspend fun handle(link: PhoneLink, raw: ByteArray): List<ByteArray> {
        val msgId = if (raw.size >= 5) raw.readU16(3) else 0
        val retry = limiter.retryAfterSeconds()
        if (retry > 0) return reply(link, plainError(msgId, ErrorCode.RATE_LIMITED, retry)) // no decryption while locked out
        val frame = try {
            Frame.decode(raw)
        } catch (e: ProtocolException) {
            return reply(link, plainError(msgId, e.code), countFailure = true)
        }
        return when {
            frame.type == MsgType.PAIR_REQ.code && !frame.plaintext -> onPairReq(link, frame)
            MsgType.fromCode(frame.type)?.isRequest == true -> onRequest(link, frame)
            else -> reply(link, plainError(msgId, ErrorCode.MALFORMED), countFailure = true)
        }
    }

    // ---- pairing --------------------------------------------------------------------------

    private fun onPairReq(link: PhoneLink, frame: Frame): List<ByteArray> {
        val session: PairingSession
        synchronized(lock) {
            session = currentPairing() ?: return reply(link, plainError(frame.msgId, ErrorCode.NOT_IN_PAIRING_MODE))
        }
        if (frame.body.size < 32 + Proto.NONCE_LEN + Proto.TAG_LEN) return pairFailure(link, session, frame, ErrorCode.MALFORMED)
        val clientPub = frame.body.copyOfRange(0, 32)
        val sealed = frame.body.copyOfRange(32, frame.body.size)
        val kPair: ByteArray
        val ltk: ByteArray
        val opened: SealedBody.Opened
        try {
            val keys = Keys.derivePairing(Crypto.sharedSecret(session.priv, clientPub), session.secret, session.pub, clientPub)
            kPair = keys.kPair; ltk = keys.ltk
            opened = SealedBody.open(kPair, frame.header + clientPub, sealed)
        } catch (e: AuthenticationException) {
            return pairFailure(link, session, frame, ErrorCode.AUTH_FAILED)
        } catch (e: ProtocolException) {
            return pairFailure(link, session, frame, ErrorCode.MALFORMED)
        }
        // Authenticated from here: errors are encrypted with K_pair (PROTOCOL.md 4.3, "Errors during pairing").
        fun err(code: ErrorCode) = reply(link, authError(kPair, frame.msgId, opened.nonce, 0, code))
        val p = opened.plaintext
        if (p.size < 9 || p.size != 9 + (p[8].toInt() and 0xFF) || (p[8].toInt() and 0xFF) > Proto.MAX_LABEL_BYTES) return err(ErrorCode.MALFORMED)
        if (skewed(p.readU64(0))) return err(ErrorCode.STALE_TIMESTAMP)
        if (store.list().size >= Proto.MAX_CLIENTS) return err(ErrorCode.TOO_MANY_CLIENTS)
        val label = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(p, 9, p.size - 9)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return err(ErrorCode.MALFORMED) // the label must be valid UTF-8
        }
        val record = ClientRecord(Keys.shortId(clientPub), ltk, label, clock.wallMs(), 0)
        try {
            store.add(record)
        } catch (e: Exception) {
            log.log(LogLevel.ERROR, "Could not store the new client: ${e.javaClass.simpleName}")
            return err(ErrorCode.INTERNAL)
        }
        synchronized(lock) { pairing = null } // single use
        log.log(LogLevel.INFO, "Paired new client '$label'")
        val header = Frame(MsgType.PAIR_RESP, frame.msgId, ByteArray(0)).header
        val plaintext = u64(clock.wallMs()) + Keys.pairConfirm(ltk, clientPub, session.pub)
        val sealedResp = SealedBody.seal(kPair, header + opened.nonce, plaintext, random = random)
        return reply(link, Frame(MsgType.PAIR_RESP, frame.msgId, sealedResp))
    }

    private fun pairFailure(link: PhoneLink, session: PairingSession, frame: Frame, code: ErrorCode): List<ByteArray> {
        synchronized(lock) {
            session.failures++
            if (session.failures >= Proto.PAIRING_MAX_FAILURES && pairing === session) {
                pairing = null
                log.log(LogLevel.WARN, "Pairing window closed after ${session.failures} failed attempts")
            }
        }
        log.log(LogLevel.WARN, "Pairing attempt rejected ($code)")
        return reply(link, plainError(frame.msgId, code), countFailure = true)
    }

    // ---- requests -------------------------------------------------------------------------

    private suspend fun onRequest(link: PhoneLink, frame: Frame): List<ByteArray> {
        var found: ClientRecord? = null
        var opened: SealedBody.Opened? = null
        for (rec in store.list()) { // trial decryption: there is no client id on the wire
            try {
                opened = SealedBody.open(rec.keys.kReq, frame.header, frame.body)
                found = rec
                break
            } catch (e: AuthenticationException) {
                continue
            } catch (e: ProtocolException) {
                continue
            }
        }
        if (found == null || opened == null) {
            log.log(LogLevel.WARN, "Request rejected: authentication failed")
            return reply(link, plainError(frame.msgId, ErrorCode.AUTH_FAILED), countFailure = true)
        }
        val kResp = found.keys.kResp
        val nonce = opened.nonce
        fun err(code: ErrorCode, counter: Long = 0) = reply(link, authError(kResp, frame.msgId, nonce, counter, code))

        val env = try {
            Envelope.unpack(opened.plaintext)
        } catch (e: ProtocolException) {
            return err(ErrorCode.MALFORMED)
        }
        if (skewed(env.timestampMs)) {
            log.log(LogLevel.WARN, "Request from '${found.label}' rejected: stale timestamp")
            return err(ErrorCode.STALE_TIMESTAMP, env.counter)
        }
        if (java.lang.Long.compareUnsigned(env.counter, found.lastCounter) <= 0) {
            log.log(LogLevel.WARN, "Request from '${found.label}' rejected: replayed counter")
            return err(ErrorCode.REPLAY, env.counter)
        }
        // Commit the counter durably BEFORE acting, only after AEAD and timestamp succeeded.
        try {
            store.updateCounter(found.id, env.counter)
        } catch (e: Exception) {
            log.log(LogLevel.ERROR, "Could not persist the request counter: ${e.javaClass.simpleName}")
            return err(ErrorCode.INTERNAL, env.counter)
        }
        limiter.recordSuccess()
        link.authenticated = true
        if (!link.busy.compareAndSet(false, true)) return err(ErrorCode.BUSY, env.counter)
        val type = MsgType.fromCode(frame.type)!!
        val body = try {
            execute(type, env.body, found)
        } catch (e: HotspotException) {
            log.log(LogLevel.WARN, "$type from '${found.label}' failed: ${e.code}")
            return err(e.code, env.counter)
        } catch (e: ProtocolException) {
            return err(e.code, env.counter)
        } finally {
            link.busy.set(false)
        }
        log.log(LogLevel.INFO, "$type from '${found.label}' handled")
        val respType = type.code or 0x80
        val header = Frame(respType, frame.msgId, ByteArray(0)).header
        val sealed = SealedBody.seal(kResp, header + nonce, Envelope(clock.wallMs(), env.counter, body).pack(), random = random)
        return reply(link, Frame(respType, frame.msgId, sealed))
    }

    private suspend fun execute(type: MsgType, body: ByteArray, client: ClientRecord): ByteArray {
        if (type == MsgType.ENABLE) {
            val (options, autoOff) = EnableBody.unpack(body)
            if (options != 0) throw HotspotException(ErrorCode.UNSUPPORTED_REQUEST, "unknown ENABLE options")
            return try {
                withTimeout(Proto.HOTSPOT_TIMEOUT_MS) { controller.enable(options, autoOff) }.encode()
            } catch (e: TimeoutCancellationException) {
                throw HotspotException(ErrorCode.HOTSPOT_FAILED, "hotspot did not start in time")
            }
        }
        if (body.isNotEmpty()) throw ProtocolException(ErrorCode.MALFORMED, "${type.name} takes no body")
        return when (type) {
            MsgType.QUERY -> controller.query().encode()
            MsgType.DISABLE -> { controller.disable(); byteArrayOf(HotspotState.OFF.code.toByte()) }
            MsgType.UNPAIR -> { store.remove(client.id); log.log(LogLevel.INFO, "Client '${client.label}' unpaired itself"); ByteArray(0) }
            else -> throw HotspotException(ErrorCode.UNSUPPORTED_REQUEST)
        }
    }
}
