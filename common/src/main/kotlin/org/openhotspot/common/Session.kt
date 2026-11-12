package org.openhotspot.common

import org.openhotspot.common.crypto.RandomSource
import org.openhotspot.common.crypto.SystemRandom

/** Building requests and verifying responses (PROTOCOL.md section 6). Used by clients and tests. */
object Session {
    class BuiltRequest(val frame: Frame, val nonce: ByteArray)

    fun buildRequest(
        kReq: ByteArray, type: MsgType, msgId: Int, timestampMs: Long, counter: Long,
        body: ByteArray = ByteArray(0), nonce: ByteArray? = null, random: RandomSource = SystemRandom,
    ): BuiltRequest {
        val header = Frame(type, msgId, ByteArray(0)).header
        val sealed = SealedBody.seal(kReq, header, Envelope(timestampMs, counter, body).pack(), nonce, random)
        return BuiltRequest(Frame(type, msgId, sealed), sealed.copyOf(Proto.NONCE_LEN))
    }

    /**
     * Verifies and decrypts a response, returning its body. Throws [PhoneException] for ERROR frames.
     * Authenticated errors are surfaced before the skew check so clock problems report the phone's
     * STALE_TIMESTAMP rather than a local complaint.
     */
    fun parseResponse(
        kResp: ByteArray, frame: Frame, requestType: MsgType, requestMsgId: Int,
        requestNonce: ByteArray, requestCounter: Long, nowMs: Long? = null,
    ): ByteArray {
        if (frame.msgId != requestMsgId) throw ProtocolException(ErrorCode.MALFORMED, "response msg_id does not match the request")
        if (frame.type == MsgType.ERROR.code && frame.plaintext) {
            val (code, retry) = ErrorBodies.unpackPlain(frame.body)
            throw PhoneException(ErrorCode.fromCode(code), code, retry, authenticated = false)
        }
        if (frame.type != MsgType.ERROR.code && frame.type != (requestType.code or 0x80)) {
            throw ProtocolException(ErrorCode.MALFORMED, "unexpected response type 0x${frame.type.toString(16)}")
        }
        val plaintext = SealedBody.open(kResp, frame.header + requestNonce, frame.body).plaintext
        if (frame.type == MsgType.ERROR.code) {
            val e = ErrorBodies.unpackAuth(plaintext)
            if (e.requestCounter != requestCounter) throw ProtocolException(ErrorCode.MALFORMED, "error counter echo does not match the request")
            throw PhoneException(ErrorCode.fromCode(e.code), e.code, e.retryAfterSeconds, authenticated = true)
        }
        val env = Envelope.unpack(plaintext)
        if (env.counter != requestCounter) throw ProtocolException(ErrorCode.MALFORMED, "response counter echo does not match the request")
        if (nowMs != null && kotlin.math.abs(env.timestampMs - nowMs) > Proto.TIMESTAMP_SKEW_MS) {
            throw ProtocolException(ErrorCode.STALE_TIMESTAMP, "response timestamp outside the allowed skew")
        }
        return env.body
    }
}
