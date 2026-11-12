package org.openhotspot.common

import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.openhotspot.common.crypto.ChaCha20Poly1305
import org.openhotspot.common.crypto.Crypto
import org.openhotspot.common.crypto.X25519

/** Known answers from the RFCs, plus differential tests against the JDK's own providers. */
class CryptoTest {
    private fun h(s: String) = s.hexToBytes()

    @Test fun x25519Rfc7748Section6() {
        val a = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.publicKey(a).toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", X25519.publicKey(b).toHex())
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", Crypto.sharedSecret(a, X25519.publicKey(b)).toHex())
    }

    @Test fun x25519Rfc7748Section52() {
        val k = h("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = h("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", X25519.scalarMult(k, u).toHex())
        val nine = ByteArray(32).also { it[0] = 9 }
        assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", X25519.scalarMult(nine, nine).toHex())
    }

    @Test fun x25519RejectsAllZeroSharedSecret() {
        assertFailsWith<AuthenticationException> { Crypto.sharedSecret(ByteArray(32) { it.toByte() }, ByteArray(32)) }
    }

    @Test fun x25519MatchesJdkOnRandomKeys() {
        val rng = SecureRandom()
        val kf = KeyFactory.getInstance("XDH")
        repeat(40) {
            val a = ByteArray(32).also(rng::nextBytes)
            val b = ByteArray(32).also(rng::nextBytes)
            val bPub = X25519.publicKey(b)
            val jdkPriv = kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, a))
            val jdkPub = kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, bPub.reversedArray())))
            val jdk = KeyAgreement.getInstance("XDH").apply { init(jdkPriv); doPhase(jdkPub, true) }.generateSecret()
            assertContentEquals(jdk, X25519.scalarMult(a, bPub))
        }
    }

    @Test fun hkdfRfc5869() {
        // test case 1
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Crypto.hkdf(ByteArray(13) { it.toByte() }, ByteArray(22) { 0x0b }, ByteArray(10) { (0xf0 + it).toByte() }, 42).toHex(),
        )
        // test case 3: empty salt and info
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Crypto.hkdf(ByteArray(0), ByteArray(22) { 0x0b }, ByteArray(0), 42).toHex(),
        )
    }

    @Test fun chacha20Poly1305Rfc8439() {
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            .toByteArray()
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = h("070000004041424344454647")
        val aad = h("50515253c0c1c2c3c4c5c6c7")
        val sealed = ChaCha20Poly1305.seal(key, nonce, aad, pt)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).toHex())
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", sealed.copyOf(16).toHex())
        assertContentEquals(pt, ChaCha20Poly1305.open(key, nonce, aad, sealed))
        assertFailsWith<AuthenticationException> { ChaCha20Poly1305.open(key, nonce, aad + 1, sealed) }
    }

    @Test fun chacha20Poly1305MatchesJdkForManyLengths() {
        val rng = SecureRandom()
        for (len in (0..130) + listOf(255, 256, 257, 511, 512, 1000)) {
            val key = ByteArray(32).also(rng::nextBytes)
            val nonce = ByteArray(12).also(rng::nextBytes)
            val aad = ByteArray(len % 41).also(rng::nextBytes)
            val pt = ByteArray(len).also(rng::nextBytes)
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            cipher.updateAAD(aad)
            val jdk = cipher.doFinal(pt)
            assertContentEquals(jdk, ChaCha20Poly1305.seal(key, nonce, aad, pt), "length $len")
            assertContentEquals(pt, ChaCha20Poly1305.open(key, nonce, aad, jdk), "length $len")
        }
    }

    @Test fun everyBitFlipIsDetected() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { it.toByte() }
        val sealed = ChaCha20Poly1305.seal(key, nonce, ByteArray(3), ByteArray(20) { 7 })
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<AuthenticationException>("byte $i") { ChaCha20Poly1305.open(key, nonce, ByteArray(3), bad) }
        }
        assertFailsWith<AuthenticationException> { ChaCha20Poly1305.open(key, nonce, ByteArray(3), ByteArray(15)) }
    }
}
