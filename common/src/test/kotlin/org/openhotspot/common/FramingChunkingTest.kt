package org.openhotspot.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingChunkingTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun codeOf(block: () -> Unit) = assertFailsWith<ProtocolException> { block() }.code

    @Test fun frameRoundTrip() {
        val f = Frame(MsgType.ENABLE, 0xBEEF, "abc".toByteArray())
        assertContentEquals(bytes(1, 0x10, 0, 0xBE, 0xEF, 0, 3) + "abc".toByteArray(), f.encode())
        assertEquals(f, Frame.decode(f.encode()))
    }

    @Test fun frameDecodeRejects() {
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame.decode(bytes(1, 0x10, 0)) })
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, codeOf { Frame.decode(bytes(2, 0x10, 0, 0, 1, 0, 0)) })
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame.decode(bytes(1, 0x77, 0, 0, 1, 0, 0)) }) // unknown type
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame.decode(bytes(1, 0x10, 2, 0, 1, 0, 0)) }) // unknown flag bit
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame.decode(bytes(1, 0x10, 1, 0, 1, 0, 0)) }) // plaintext flag on non-error
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame.decode(bytes(1, 0x10, 0, 0, 1, 0, 5, 'a'.code, 'b'.code)) })
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame.decode(bytes(1, 0x10, 0, 0, 1, 0, 0, 'x'.code)) })
    }

    @Test fun frameSizeLimit() {
        Frame(MsgType.ENABLE, 1, ByteArray(505)).encode()
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame(MsgType.ENABLE, 1, ByteArray(506)).encode() })
        assertEquals(ErrorCode.MALFORMED, codeOf { Frame.decode(bytes(1, 0x10, 0, 0, 1, 1, 0xfa) + ByteArray(506)) })
    }

    @Test fun plaintextErrorFrame() {
        val f = Frame(MsgType.ERROR, 1, bytes(3, 0, 30), Proto.FLAG_PLAINTEXT)
        assertTrue(Frame.decode(f.encode()).plaintext)
    }

    @Test fun hotspotInfoRoundTrip() {
        val info = HotspotInfo(HotspotState.ON, Security.WPA3_SAE, Band.GHZ_6, "ünï".toByteArray(), "p".repeat(63))
        assertEquals(info, HotspotInfo.decode(info.encode()))
        val off = HotspotInfo(HotspotState.OFF)
        assertContentEquals(bytes(0, 1, 0, 0, 0), off.encode())
        assertEquals(off, HotspotInfo.decode(off.encode()))
        assertFalse(info.toString().contains("ppppp"), "toString must not reveal the passphrase")
    }

    @Test fun hotspotInfoRejects() {
        listOf(
            ByteArray(0), bytes(2, 1, 1, 33) + ByteArray(40),
            bytes(2, 1, 1, 1, 'a'.code, 7) + ByteArray(7) { 'x'.code.toByte() },
            bytes(2, 1, 1, 1, 'a'.code, 8) + ByteArray(8) { 'x'.code.toByte() } + bytes(33),
            bytes(9, 1, 1, 0, 0),
            bytes(2, 1, 1, 0, 8) + ByteArray(8) { 0xff.toByte() }, // invalid UTF-8 passphrase
        ).forEach { assertFailsWith<ProtocolException> { HotspotInfo.decode(it) } }
        assertFailsWith<ProtocolException> { HotspotInfo(HotspotState.ON, ssid = ByteArray(33)).encode() }
        assertFailsWith<ProtocolException> { HotspotInfo(HotspotState.ON, passphrase = "short").encode() }
    }

    @Test fun enableBodyAndStatus() {
        assertEquals(0 to 600, EnableBody.unpack(EnableBody.pack(0, 600)))
        assertFailsWith<ProtocolException> { EnableBody.unpack(bytes(0)) }
        val s = Status(1, ready = true, pairingOpen = false, hotspotActive = true)
        assertContentEquals(bytes(1, 5), s.encode())
        assertEquals(s, Status.decode(s.encode()))
        assertFailsWith<ProtocolException> { Status.decode(bytes(1, 8)) }
    }

    // ---- chunking ----

    @Test fun singleChunkHeaderIs0xC0() {
        assertContentEquals(bytes(0xC0) + ByteArray(10) { 1 }, Chunking.chunkFrame(ByteArray(10) { 1 }, 23).single())
    }

    @Test fun chunkSizesAndFlags() {
        val chunks = Chunking.chunkFrame(ByteArray(50) { it.toByte() }, 23)
        assertEquals(listOf(20, 20, 13), chunks.map { it.size })
        assertEquals(listOf(0x80, 0x01, 0x42), chunks.map { it.u8(0) })
    }

    @Test fun roundTripAllSizes() {
        for (mtu in listOf(23, 24, 100, 185, 247, 512)) for (size in listOf(7, 19, 20, 38, 151, 512)) {
            val frame = Frame(MsgType.ENABLE, 7, ByteArray(size - 7)).encode()
            val r = Reassembler({ 0L })
            var out: ByteArray? = null
            for (c in Chunking.chunkFrame(frame, mtu)) {
                assertTrue(c.size <= mtu - 3)
                out = r.feed(c)
            }
            assertContentEquals(frame, out, "mtu $mtu size $size")
            assertFalse(r.inProgress)
        }
    }

    private fun frame(n: Int = 40) = Frame(MsgType.ENABLE, 1, ByteArray(n)).encode()

    @Test fun mtuBelowMinimum() {
        assertFailsWith<IllegalArgumentException> { Chunking.chunkFrame(frame(), 22) }
    }

    @Test fun gapOrOrphanDiscards() {
        val chunks = Chunking.chunkFrame(frame(60), 23)
        val r = Reassembler({ 0L })
        r.feed(chunks[0])
        assertFailsWith<ProtocolException> { r.feed(chunks[2]) }
        assertFalse(r.inProgress)
        assertFailsWith<ProtocolException> { r.feed(chunks[1]) }
        assertFailsWith<ProtocolException> { Reassembler({ 0L }).feed(bytes(0x41, 'a'.code, 'b'.code)) }
    }

    @Test fun firstRestartsAPartialFrame() {
        val a = Chunking.chunkFrame(frame(60), 23)
        val b = Chunking.chunkFrame(frame(10), 23)
        val r = Reassembler({ 0L })
        r.feed(a[0])
        assertContentEquals(frame(10), r.feed(b[0]))
    }

    @Test fun malformedChunkHeaders() {
        assertFailsWith<ProtocolException> { Reassembler({ 0L }).feed(bytes(0x85) + frame()) } // FIRST with seq != 0
        assertFailsWith<ProtocolException> { Reassembler({ 0L }).feed(bytes(0xC0) + frame() + bytes(0)) } // extra data
        assertFailsWith<ProtocolException> { Reassembler({ 0L }).feed(bytes(0x80, 1, 0x10)) } // shorter than a header
        assertFailsWith<ProtocolException> { Reassembler({ 0L }).feed(bytes(0x80, 1, 0x10, 0, 0, 1, 2, 0) + ByteArray(5)) } // declares > 512
        val chunks = Chunking.chunkFrame(frame(60), 23)
        assertFailsWith<ProtocolException> { // LAST set before the declared length is reached
            Reassembler({ 0L }).feed(byteArrayOf((chunks[0][0].toInt() or 0x40).toByte()) + chunks[0].copyOfRange(1, chunks[0].size))
        }
        val r = Reassembler({ 0L })
        chunks.dropLast(1).forEach { r.feed(it) }
        val last = chunks.last()
        assertFailsWith<ProtocolException> { // complete length but LAST missing
            r.feed(byteArrayOf((last[0].toInt() and 0x40.inv()).toByte()) + last.copyOfRange(1, last.size))
        }
    }

    @Test fun reassemblyTimeout() {
        var t = 0L
        val chunks = Chunking.chunkFrame(frame(60), 23)
        val r = Reassembler({ t })
        r.feed(chunks[0])
        t = 5_100
        assertFailsWith<ProtocolException> { r.feed(chunks[1]) } // stale partial dropped, continuation is orphaned
        t = 0
        val r2 = Reassembler({ t })
        r2.feed(chunks[0]); t = 4_900
        assertNull(r2.feed(chunks[1])) // still inside the 5 s window
    }
}
