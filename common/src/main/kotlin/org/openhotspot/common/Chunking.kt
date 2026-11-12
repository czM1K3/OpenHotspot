package org.openhotspot.common

/** Chunking and reassembly (PROTOCOL.md section 8). */
object Chunking {
    const val FIRST = 0x80
    const val LAST = 0x40
    const val SEQ_MASK = 0x3F

    /** Splits an encoded frame into chunks that fit one ATT write/notification at [mtu]. */
    fun chunkFrame(frame: ByteArray, mtu: Int): List<ByteArray> {
        require(mtu >= Proto.MIN_MTU) { "MTU must be at least ${Proto.MIN_MTU}" }
        require(frame.isNotEmpty()) { "empty frame" }
        val size = mtu - 3 - 1
        val count = (frame.size + size - 1) / size
        return List(count) { i ->
            val flags = (if (i == 0) FIRST else 0) or (if (i == count - 1) LAST else 0) or (i and SEQ_MASK)
            byteArrayOf(flags.toByte()) + frame.copyOfRange(i * size, minOf(frame.size, (i + 1) * size))
        }
    }
}

/** Reassembles chunks into frames. Any violation throws MALFORMED and discards the partial frame. */
class Reassembler(
    private val monotonicMs: () -> Long,
    private val timeoutMs: Long = Proto.REASSEMBLY_TIMEOUT_MS,
) {
    private var buf = java.io.ByteArrayOutputStream()
    private var nextSeq = 0
    private var total = 0
    private var startedMs = 0L
    var inProgress = false
        private set

    fun reset() {
        buf = java.io.ByteArrayOutputStream()
        nextSeq = 0; total = 0; startedMs = 0; inProgress = false
    }

    private fun fail(msg: String): ProtocolException {
        reset()
        return ProtocolException(ErrorCode.MALFORMED, msg)
    }

    /** Feeds one chunk; returns the complete frame bytes when finished, else null. */
    fun feed(chunk: ByteArray): ByteArray? {
        if (chunk.size < 2) throw fail("empty chunk")
        if (inProgress && monotonicMs() - startedMs > timeoutMs) reset() // stale partial frame is dropped
        val head = chunk.u8(0)
        val first = head and Chunking.FIRST != 0
        val last = head and Chunking.LAST != 0
        val seq = head and Chunking.SEQ_MASK
        if (first) {
            reset()
            if (seq != 0) throw fail("FIRST chunk must have sequence 0")
            inProgress = true
            startedMs = monotonicMs()
        } else if (!inProgress) {
            throw fail("continuation chunk with nothing in progress")
        } else if (seq != nextSeq) {
            throw fail("chunk sequence gap")
        }
        nextSeq = (seq + 1) and Chunking.SEQ_MASK
        buf.write(chunk, 1, chunk.size - 1)
        if (first) {
            if (buf.size() < Proto.FRAME_HEADER_LEN) throw fail("first chunk shorter than frame header")
            total = Proto.FRAME_HEADER_LEN + buf.toByteArray().readU16(5)
            if (total > Proto.MAX_FRAME) throw fail("frame exceeds 512 bytes")
        }
        if (buf.size() > total) throw fail("more data than the frame header declares")
        val complete = buf.size() == total
        if (complete != last) throw fail("LAST flag disagrees with frame length")
        if (!complete) return null
        val frame = buf.toByteArray()
        reset()
        return frame
    }
}
