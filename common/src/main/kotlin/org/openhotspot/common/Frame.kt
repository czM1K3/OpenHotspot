package org.openhotspot.common

import org.openhotspot.common.crypto.Crypto
import org.openhotspot.common.crypto.RandomSource
import org.openhotspot.common.crypto.SystemRandom

/** Frame envelope (PROTOCOL.md section 5): version | type | flags | msg_id(2) | body_len(2) | body. */
class Frame(val type: Int, val msgId: Int, val body: ByteArray, val flags: Int = 0) {
    constructor(type: MsgType, msgId: Int, body: ByteArray, flags: Int = 0) : this(type.code, msgId, body, flags)

    /** The 5 header bytes (version, type, flags, msg_id), used as AAD. */
    val header: ByteArray
        get() = byteArrayOf(Proto.VERSION.toByte(), type.toByte(), flags.toByte(), (msgId ushr 8).toByte(), msgId.toByte())

    val plaintext: Boolean get() = flags and Proto.FLAG_PLAINTEXT != 0

    fun encode(): ByteArray {
        if (Proto.FRAME_HEADER_LEN + body.size > Proto.MAX_FRAME) throw ProtocolException(ErrorCode.MALFORMED, "frame exceeds 512 bytes")
        return header + u16(body.size) + body
    }

    override fun equals(other: Any?) =
        other is Frame && type == other.type && msgId == other.msgId && flags == other.flags && body.contentEquals(other.body)

    override fun hashCode() = 31 * (31 * type + msgId) + body.contentHashCode()

    companion object {
        fun decode(raw: ByteArray): Frame {
            if (raw.size < Proto.FRAME_HEADER_LEN) throw ProtocolException(ErrorCode.MALFORMED, "frame shorter than header")
            if (raw.size > Proto.MAX_FRAME) throw ProtocolException(ErrorCode.MALFORMED, "frame exceeds 512 bytes")
            val version = raw.u8(0)
            val type = raw.u8(1)
            val flags = raw.u8(2)
            val msgId = raw.readU16(3)
            val bodyLen = raw.readU16(5)
            if (version != Proto.VERSION) throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "version $version")
            if (MsgType.fromCode(type) == null) throw ProtocolException(ErrorCode.MALFORMED, "unknown type 0x${type.toString(16)}")
            if (flags and Proto.FLAG_PLAINTEXT.inv() != 0 || (flags != 0 && type != MsgType.ERROR.code)) {
                throw ProtocolException(ErrorCode.MALFORMED, "invalid flags")
            }
            if (raw.size != Proto.FRAME_HEADER_LEN + bodyLen) throw ProtocolException(ErrorCode.MALFORMED, "body length mismatch")
            return Frame(type, msgId, raw.copyOfRange(Proto.FRAME_HEADER_LEN, raw.size), flags)
        }
    }
}

/** Encrypted bodies are `nonce(12) || ciphertext || tag(16)` (PROTOCOL.md section 5). */
object SealedBody {
    class Opened(val plaintext: ByteArray, val nonce: ByteArray)

    /** A random nonce is used unless [nonce] is given (tests only: never derive nonces from counters). */
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray, nonce: ByteArray? = null, random: RandomSource = SystemRandom): ByteArray {
        val n = nonce ?: Crypto.randomNonce(random)
        return n + Crypto.seal(key, n, aad, plaintext)
    }

    fun split(body: ByteArray): Pair<ByteArray, ByteArray> {
        if (body.size < Proto.NONCE_LEN + Proto.TAG_LEN) throw ProtocolException(ErrorCode.MALFORMED, "encrypted body too short")
        return body.copyOfRange(0, Proto.NONCE_LEN) to body.copyOfRange(Proto.NONCE_LEN, body.size)
    }

    fun open(key: ByteArray, aad: ByteArray, body: ByteArray): Opened {
        val (nonce, ct) = split(body)
        return Opened(Crypto.open(key, nonce, aad, ct), nonce)
    }
}
