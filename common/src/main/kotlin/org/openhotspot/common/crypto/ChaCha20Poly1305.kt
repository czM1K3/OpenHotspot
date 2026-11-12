package org.openhotspot.common.crypto

import org.openhotspot.common.AuthenticationException
import org.openhotspot.common.Proto
import org.openhotspot.common.constantTimeEquals

/** ChaCha20 stream cipher, RFC 8439 section 2.4. */
internal object ChaCha20 {
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    fun block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val init = IntArray(16)
        init[0] = 0x61707865; init[1] = 0x3320646e; init[2] = 0x79622d32; init[3] = 0x6b206574
        for (i in 0 until 8) init[4 + i] = le32(key, 4 * i)
        init[12] = counter
        for (i in 0 until 3) init[13 + i] = le32(nonce, 4 * i)
        val w = init.copyOf()
        repeat(10) {
            qr(w, 0, 4, 8, 12); qr(w, 1, 5, 9, 13); qr(w, 2, 6, 10, 14); qr(w, 3, 7, 11, 15)
            qr(w, 0, 5, 10, 15); qr(w, 1, 6, 11, 12); qr(w, 2, 7, 8, 13); qr(w, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = w[i] + init[i]
            out[4 * i] = v.toByte(); out[4 * i + 1] = (v ushr 8).toByte()
            out[4 * i + 2] = (v ushr 16).toByte(); out[4 * i + 3] = (v ushr 24).toByte()
        }
        return out
    }

    fun xor(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var off = 0
        var ctr = counter
        while (off < data.size) {
            val ks = block(key, ctr++, nonce)
            val n = minOf(64, data.size - off)
            for (i in 0 until n) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
        return out
    }
}

/** Poly1305 one-time authenticator, RFC 8439 section 2.5 (26-bit limbs, after poly1305-donna). */
internal object Poly1305 {
    private const val M26 = 0x3ffffffL

    private fun le32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))

    fun mac(key: ByteArray, msg: ByteArray): ByteArray {
        val r0 = le32(key, 0) and 0x3ffffff
        val r1 = (le32(key, 3) ushr 2) and 0x3ffff03
        val r2 = (le32(key, 6) ushr 4) and 0x3ffc0ff
        val r3 = (le32(key, 9) ushr 6) and 0x3f03fff
        val r4 = (le32(key, 12) ushr 8) and 0x00fffff
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val blk = ByteArray(16)
        var off = 0
        while (off < msg.size) {
            val n = minOf(16, msg.size - off)
            blk.fill(0)
            msg.copyInto(blk, 0, off, off + n)
            val hibit: Long
            if (n < 16) { blk[n] = 1; hibit = 0 } else hibit = 1L shl 24
            h0 += le32(blk, 0) and M26
            h1 += (le32(blk, 3) ushr 2) and M26
            h2 += (le32(blk, 6) ushr 4) and M26
            h3 += (le32(blk, 9) ushr 6) and M26
            h4 += (le32(blk, 12) ushr 8) or hibit
            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and M26
            d1 += c; c = d1 ushr 26; h1 = d1 and M26
            d2 += c; c = d2 ushr 26; h2 = d2 and M26
            d3 += c; c = d3 ushr 26; h3 = d3 and M26
            d4 += c; c = d4 ushr 26; h4 = d4 and M26
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and M26; h1 += c
            off += n
        }
        var c = h1 ushr 26; h1 = h1 and M26
        h2 += c; c = h2 ushr 26; h2 = h2 and M26
        h3 += c; c = h3 ushr 26; h3 = h3 and M26
        h4 += c; c = h4 ushr 26; h4 = h4 and M26
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and M26
        h1 += c
        // g = h + 5 - 2^130; keep h if g went negative (h < p), else g, without branching
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and M26
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and M26
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and M26
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and M26
        val g4 = h4 + c - (1L shl 26)
        val keepH = g4 shr 63
        h0 = (h0 and keepH) or (g0 and keepH.inv())
        h1 = (h1 and keepH) or (g1 and keepH.inv())
        h2 = (h2 and keepH) or (g2 and keepH.inv())
        h3 = (h3 and keepH) or (g3 and keepH.inv())
        h4 = (h4 and keepH) or ((g4 and M26) and keepH.inv())
        val t0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val t1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val t2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val t3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        val out = ByteArray(16)
        val words = longArrayOf(t0, t1, t2, t3)
        var f = 0L
        for (i in 0 until 4) {
            f = words[i] + le32(key, 16 + 4 * i) + (f ushr 32)
            for (j in 0 until 4) out[4 * i + j] = (f ushr (8 * j)).toByte()
        }
        return out
    }
}

/** ChaCha20-Poly1305 AEAD, RFC 8439 section 2.8. Output is ciphertext followed by the 16-byte tag. */
object ChaCha20Poly1305 {
    private fun pad16(b: ByteArray) = b.copyOf(b.size + (16 - b.size % 16) % 16)

    private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }

    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray {
        val otk = ChaCha20.block(key, 0, nonce).copyOf(32)
        val data = pad16(aad) + pad16(ct) + le64(aad.size.toLong()) + le64(ct.size.toLong())
        return Poly1305.mac(otk, data)
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce.size == Proto.NONCE_LEN) { "nonce must be 12 bytes" }
        val ct = ChaCha20.xor(key, 1, nonce, plaintext)
        return ct + tag(key, nonce, aad, ct)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce.size == Proto.NONCE_LEN) { "nonce must be 12 bytes" }
        if (ciphertextAndTag.size < Proto.TAG_LEN) throw AuthenticationException("ciphertext shorter than the tag")
        val ct = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - Proto.TAG_LEN)
        val given = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - Proto.TAG_LEN, ciphertextAndTag.size)
        if (!constantTimeEquals(given, tag(key, nonce, aad, ct))) throw AuthenticationException("AEAD authentication failed")
        return ChaCha20.xor(key, 1, nonce, ct)
    }
}
