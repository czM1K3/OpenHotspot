package org.openhotspot.phone.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption of small blobs. Output format is implementation-defined but self-contained. */
interface SecretCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray

    @Throws(GeneralSecurityException::class)
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/** AES-256-GCM. [blob] = iv(12) || ciphertext || tag(16). Shared by the Keystore and JVM-test implementations. */
abstract class AesGcmCipher : SecretCipher {
    protected abstract fun key(): SecretKey

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key()) // the provider generates a fresh random IV
        c.updateAAD(aad)
        return c.iv + c.doFinal(plaintext)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < IV_LEN + 16) throw GeneralSecurityException("blob too short")
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
        c.updateAAD(aad)
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

/**
 * Wraps data with a non-exportable AES-256 key held in the Android Keystore (StrongBox when the
 * device has it, otherwise TEE/software-backed). The key never leaves the Keystore, so a copy of
 * the app's files alone does not reveal the client keys. No user authentication is required,
 * because the service must work while the phone is locked.
 */
class AndroidKeystoreCipher(private val alias: String = ALIAS) : AesGcmCipher() {
    @Volatile private var cached: SecretKey? = null

    @Synchronized
    override fun key(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        val key = existing ?: generate()
        cached = key
        return key
    }

    private fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .apply { if (strongBox) setIsStrongBoxBacked(true) }
        .build()

    private fun generate(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "openhotspot.clients.v1"
    }
}
