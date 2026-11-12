package org.openhotspot.common

/** Plaintext layouts inside encrypted frames (PROTOCOL.md sections 5 and 9). */
private fun malformed(msg: String) = ProtocolException(ErrorCode.MALFORMED, msg)

/** `timestamp_ms(8) | counter(8) | body`; the response envelope has the same shape with the request counter. */
class Envelope(val timestampMs: Long, val counter: Long, val body: ByteArray) {
    fun pack(): ByteArray = u64(timestampMs) + u64(counter) + body

    companion object {
        fun unpack(plaintext: ByteArray): Envelope {
            if (plaintext.size < 16) throw malformed("plaintext too short")
            return Envelope(plaintext.readU64(0), plaintext.readU64(8), plaintext.copyOfRange(16, plaintext.size))
        }
    }
}

object EnableBody {
    fun pack(options: Int = 0, autoOffSeconds: Int = 0): ByteArray = byteArrayOf(options.toByte()) + u16(autoOffSeconds)

    /** Returns (options, autoOffSeconds). */
    fun unpack(body: ByteArray): Pair<Int, Int> {
        if (body.size != 3) throw malformed("ENABLE body must be 3 bytes")
        return body.u8(0) to body.readU16(1)
    }
}

class HotspotInfo(
    val state: HotspotState,
    val security: Security = Security.WPA2_PSK,
    val band: Band = Band.UNKNOWN,
    val ssid: ByteArray = ByteArray(0),
    val passphrase: String = "",
) {
    fun encode(): ByteArray {
        val pw = passphrase.toByteArray(Charsets.UTF_8)
        if (ssid.size > 32) throw malformed("SSID longer than 32 bytes")
        if (pw.isNotEmpty() && pw.size !in 8..63) throw malformed("passphrase must be 8..63 bytes")
        return byteArrayOf(state.code.toByte(), security.code.toByte(), band.code.toByte(), ssid.size.toByte()) +
            ssid + byteArrayOf(pw.size.toByte()) + pw
    }

    override fun equals(other: Any?) = other is HotspotInfo && state == other.state && security == other.security &&
        band == other.band && ssid.contentEquals(other.ssid) && passphrase == other.passphrase

    override fun hashCode() = listOf(state, security, band, ssid.contentHashCode(), passphrase).hashCode()

    /** Deliberately omits the passphrase so it cannot reach a log by accident. */
    override fun toString() = "HotspotInfo($state, $security, $band, ssid=${ssid.toString(Charsets.UTF_8)}, passphrase=<hidden>)"

    companion object {
        fun decode(body: ByteArray): HotspotInfo {
            if (body.size < 5) throw malformed("hotspot info too short")
            val ssidLen = body.u8(3)
            if (ssidLen > 32 || body.size < 5 + ssidLen) throw malformed("bad SSID length")
            val passLen = body.u8(4 + ssidLen)
            if (passLen != 0 && passLen !in 8..63) throw malformed("bad passphrase length")
            if (body.size != 5 + ssidLen + passLen) throw malformed("hotspot info length mismatch")
            val passBytes = body.copyOfRange(5 + ssidLen, body.size)
            val pass = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(passBytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw malformed("passphrase is not valid UTF-8")
            }
            return HotspotInfo(
                HotspotState.fromCode(body.u8(0)) ?: throw malformed("unknown state"),
                Security.fromCode(body.u8(1)) ?: throw malformed("unknown security"),
                Band.fromCode(body.u8(2)) ?: throw malformed("unknown band"),
                body.copyOfRange(4, 4 + ssidLen),
                pass,
            )
        }
    }
}

object ErrorBodies {
    fun packPlain(code: Int, retryAfterSeconds: Int = 0): ByteArray = byteArrayOf(code.toByte()) + u16(retryAfterSeconds)

    /** Returns (code, retryAfterSeconds). */
    fun unpackPlain(body: ByteArray): Pair<Int, Int> {
        if (body.size != 3) throw malformed("plain error body must be 3 bytes")
        return body.u8(0) to body.readU16(1)
    }

    fun packAuth(timestampMs: Long, requestCounter: Long, code: Int, retryAfterSeconds: Int = 0): ByteArray =
        u64(timestampMs) + u64(requestCounter) + byteArrayOf(code.toByte()) + u16(retryAfterSeconds)

    class Auth(val timestampMs: Long, val requestCounter: Long, val code: Int, val retryAfterSeconds: Int)

    fun unpackAuth(plaintext: ByteArray): Auth {
        if (plaintext.size != 19) throw malformed("authenticated error plaintext must be 19 bytes")
        return Auth(plaintext.readU64(0), plaintext.readU64(8), plaintext.u8(16), plaintext.readU16(17))
    }
}

data class Status(val version: Int, val ready: Boolean, val pairingOpen: Boolean, val hotspotActive: Boolean) {
    fun encode(): ByteArray = byteArrayOf(
        version.toByte(),
        ((if (ready) 1 else 0) or (if (pairingOpen) 2 else 0) or (if (hotspotActive) 4 else 0)).toByte(),
    )

    companion object {
        fun decode(raw: ByteArray): Status {
            if (raw.size != 2 || raw.u8(1) and 0xF8 != 0) throw malformed("bad status")
            return Status(raw.u8(0), raw.u8(1) and 1 != 0, raw.u8(1) and 2 != 0, raw.u8(1) and 4 != 0)
        }
    }
}
