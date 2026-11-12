package org.openhotspot.common.crypto

/**
 * X25519 (RFC 7748), a port of the public-domain TweetNaCl `crypto_scalarmult`.
 *
 * Why not the platform provider: Android only guarantees X25519 from API 33 and our minSdk is 30
 * (docs/DECISIONS.md D9). The code has no secret-dependent branches or table lookups; the JVM
 * gives no hard constant-time guarantee. Verified against RFC 7748 vectors and, in the tests,
 * against the JDK's own X25519.
 */
object X25519 {
    private val K121665 = LongArray(16).also { it[0] = 0xDB41; it[1] = 1 }
    private val BASE = ByteArray(32).also { it[0] = 9 }

    private fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        car(t); car(t); car(t)
        val m = LongArray(16)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    private fun unpack(n: ByteArray): LongArray {
        val o = LongArray(16) { (n[2 * it].toLong() and 0xff) + ((n[2 * it + 1].toLong() and 0xff) shl 8) }
        o[15] = o[15] and 0x7fff
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }
    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car(o); car(o)
    }

    private fun sqr(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv(i: LongArray): LongArray {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            sqr(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        return c
    }

    /** Raw scalar multiplication. The result may be all zero for low-order points; see [Crypto]. */
    fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        require(n.size == 32 && p.size == 32) { "X25519 keys are 32 bytes" }
        val z = n.copyOf()
        z[31] = ((n[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = unpack(p)
        val a = LongArray(16); val b = x.copyOf(); val c = LongArray(16); val d = LongArray(16)
        val e = LongArray(16); val f = LongArray(16)
        a[0] = 1; d[0] = 1
        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            sel(a, b, r); sel(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            sqr(d, e); sqr(f, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); sqr(b, a); sub(c, d, f)
            mul(a, c, K121665); add(a, a, d); mul(c, c, a); mul(a, d, f); mul(d, b, x); sqr(b, e)
            sel(a, b, r); sel(c, d, r)
        }
        mul(a, a, inv(c))
        return pack(a)
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)
}
