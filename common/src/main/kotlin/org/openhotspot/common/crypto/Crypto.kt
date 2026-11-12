package org.openhotspot.common.crypto

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.openhotspot.common.AuthenticationException
import org.openhotspot.common.Proto

interface RandomSource {
    fun bytes(n: Int): ByteArray
}

object SystemRandom : RandomSource {
    private val rng = SecureRandom()
    override fun bytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
}

/** Facade over the primitives of PROTOCOL.md section 1. */
object Crypto {
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** HKDF-SHA256 (RFC 5869). An empty salt means 32 zero bytes. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        var t = ByteArray(0)
        val okm = java.io.ByteArrayOutputStream()
        var i = 1
        while (okm.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            okm.write(t)
            i++
        }
        return okm.toByteArray().copyOf(length)
    }

    fun generatePrivateKey(random: RandomSource = SystemRandom): ByteArray = random.bytes(32)

    fun publicKey(privateKey: ByteArray): ByteArray = X25519.publicKey(privateKey)

    /** X25519 shared secret. An all-zero result (low-order peer key) is rejected. */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val shared = X25519.scalarMult(privateKey, peerPublic)
        var acc = 0
        for (b in shared) acc = acc or b.toInt()
        if (acc == 0) throw AuthenticationException("X25519 produced an all-zero shared secret")
        return shared
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        ChaCha20Poly1305.seal(key, nonce, aad, plaintext)

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray =
        ChaCha20Poly1305.open(key, nonce, aad, ciphertextAndTag)

    fun randomNonce(random: RandomSource = SystemRandom): ByteArray = random.bytes(Proto.NONCE_LEN)
}
