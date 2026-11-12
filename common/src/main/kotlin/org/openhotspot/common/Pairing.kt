package org.openhotspot.common

import java.security.MessageDigest
import java.util.Base64
import org.openhotspot.common.crypto.Crypto
import org.openhotspot.common.crypto.RandomSource
import org.openhotspot.common.crypto.SystemRandom

/** QR payload: `version(1) | service UUID(16) | phone_pub(32) | pairing_secret(16)` (PROTOCOL.md 4.1). */
class PairingQr(val version: Int, val serviceUuid: ByteArray, val phonePub: ByteArray, val secret: ByteArray) {
    fun encode(): String {
        val raw = byteArrayOf(version.toByte()) + serviceUuid + phonePub + secret
        return Proto.QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    companion object {
        fun parse(text: String): PairingQr {
            val t = text.trim()
            if (!t.startsWith(Proto.QR_PREFIX)) throw PairingException("not an OpenHotspot pairing QR (bad prefix)")
            val raw = try {
                Base64.getUrlDecoder().decode(t.removePrefix(Proto.QR_PREFIX))
            } catch (e: IllegalArgumentException) {
                throw PairingException("pairing QR is not valid base64url")
            }
            if (raw.size != Proto.QR_RAW_LEN) throw PairingException("pairing QR payload must be ${Proto.QR_RAW_LEN} bytes, got ${raw.size}")
            if (raw.u8(0) != Proto.VERSION) throw PairingException("unsupported pairing QR version ${raw.u8(0)}")
            if (!raw.copyOfRange(1, 17).contentEquals(Proto.SERVICE_UUID_BYTES)) throw PairingException("pairing QR is for a different service UUID")
            return PairingQr(raw.u8(0), raw.copyOfRange(1, 17), raw.copyOfRange(17, 49), raw.copyOfRange(49, 65))
        }
    }
}

class SessionKeys(val kReq: ByteArray, val kResp: ByteArray, val kAdv: ByteArray)

class PairingKeys(val kPair: ByteArray, val ltk: ByteArray)

object Keys {
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** K_pair and LTK from the X25519 secret (PROTOCOL.md 4.3). */
    fun derivePairing(dh: ByteArray, secret: ByteArray, phonePub: ByteArray, clientPub: ByteArray): PairingKeys {
        val transcript = Proto.SERVICE_UUID_BYTES + phonePub + clientPub
        return PairingKeys(
            Crypto.hkdf(secret, dh, ascii("OHS1 pair") + transcript),
            Crypto.hkdf(secret, dh, ascii("OHS1 ltk") + transcript),
        )
    }

    fun deriveSession(ltk: ByteArray) = SessionKeys(
        Crypto.hkdf(ByteArray(0), ltk, ascii("OHS1 req")),
        Crypto.hkdf(ByteArray(0), ltk, ascii("OHS1 resp")),
        Crypto.hkdf(ByteArray(0), ltk, ascii("OHS1 adv")),
    )

    fun pairConfirm(ltk: ByteArray, clientPub: ByteArray, phonePub: ByteArray): ByteArray =
        Crypto.hmacSha256(ltk, ascii("OHS1 pair confirm") + clientPub + phonePub).copyOf(16)

    /** Short local identifier for a public key. Never sent over the air. */
    fun shortId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(4).toHex()
}

/** What a client keeps after pairing. [ltk] is secret. */
class PairedPhone(val phoneId: String, val ltk: ByteArray, val phonePub: ByteArray, val label: String) {
    val keys: SessionKeys by lazy { Keys.deriveSession(ltk) }
}

/** Client side of the handshake: build the PAIR_REQ, then feed the PAIR_RESP to [finish]. */
class ClientPairing(
    private val qr: PairingQr,
    private val label: String,
    private val nowMs: () -> Long,
    random: RandomSource = SystemRandom,
    privateKey: ByteArray? = null,
    private val nonce: ByteArray? = null,
    msgId: Int? = null,
) {
    private val priv = privateKey ?: Crypto.generatePrivateKey(random)
    val clientPub: ByteArray = Crypto.publicKey(priv)
    private val kPair: ByteArray
    private val ltk: ByteArray
    private val msgId: Int = msgId ?: (random.bytes(2).readU16(0))
    private val rnd = random
    private var reqNonce: ByteArray? = null

    init {
        if (label.toByteArray().size > Proto.MAX_LABEL_BYTES) throw PairingException("label longer than ${Proto.MAX_LABEL_BYTES} bytes")
        val keys = Keys.derivePairing(Crypto.sharedSecret(priv, qr.phonePub), qr.secret, qr.phonePub, clientPub)
        kPair = keys.kPair; ltk = keys.ltk
    }

    fun requestFrame(): Frame {
        val l = label.toByteArray()
        val plaintext = u64(nowMs()) + byteArrayOf(l.size.toByte()) + l
        val header = Frame(MsgType.PAIR_REQ, msgId, ByteArray(0)).header
        val sealed = SealedBody.seal(kPair, header + clientPub, plaintext, nonce, rnd)
        reqNonce = sealed.copyOf(Proto.NONCE_LEN)
        return Frame(MsgType.PAIR_REQ, msgId, clientPub + sealed)
    }

    fun finish(frame: Frame): PairedPhone {
        val rn = reqNonce ?: throw PairingException("requestFrame() has not been built")
        if (frame.msgId != msgId) throw ProtocolException(ErrorCode.MALFORMED, "PAIR_RESP msg_id mismatch")
        if (frame.type == MsgType.ERROR.code) {
            if (frame.plaintext) {
                val (code, retry) = ErrorBodies.unpackPlain(frame.body)
                throw PhoneException(ErrorCode.fromCode(code), code, retry, authenticated = false)
            }
            val e = ErrorBodies.unpackAuth(SealedBody.open(kPair, frame.header + rn, frame.body).plaintext)
            throw PhoneException(ErrorCode.fromCode(e.code), e.code, e.retryAfterSeconds, authenticated = true)
        }
        if (frame.type != MsgType.PAIR_RESP.code || frame.plaintext) throw ProtocolException(ErrorCode.MALFORMED, "expected PAIR_RESP")
        val plaintext = SealedBody.open(kPair, frame.header + rn, frame.body).plaintext
        if (plaintext.size != 24) throw ProtocolException(ErrorCode.MALFORMED, "PAIR_RESP plaintext must be 24 bytes")
        if (kotlin.math.abs(plaintext.readU64(0) - nowMs()) > Proto.TIMESTAMP_SKEW_MS) {
            throw PairingException("PAIR_RESP timestamp outside the allowed skew (check clocks)")
        }
        if (!constantTimeEquals(plaintext.copyOfRange(8, 24), Keys.pairConfirm(ltk, clientPub, qr.phonePub))) {
            throw AuthenticationException("key confirmation from the phone did not match")
        }
        return PairedPhone(Keys.shortId(qr.phonePub), ltk, qr.phonePub, label)
    }
}
