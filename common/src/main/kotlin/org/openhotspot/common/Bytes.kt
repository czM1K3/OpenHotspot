package org.openhotspot.common

import java.security.MessageDigest

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append("0123456789abcdef"[(b.toInt() shr 4) and 15]).append("0123456789abcdef"[b.toInt() and 15])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var off = 0
    for (p in parts) {
        p.copyInto(out, off)
        off += p.size
    }
    return out
}

fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

fun u64(v: Long): ByteArray = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

fun ByteArray.readU16(off: Int): Int = ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

/** Reads an unsigned 64-bit big-endian value into a Long (compare with [java.lang.Long.compareUnsigned]). */
fun ByteArray.readU64(off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (this[off + i].toLong() and 0xFF)
    return v
}

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

fun uuidToBytes(uuid: String): ByteArray = uuid.replace("-", "").hexToBytes()
