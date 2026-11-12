package org.openhotspot.phone.data

import androidx.core.content.edit
import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.openhotspot.common.Band
import org.openhotspot.common.Security
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.phone.hotspot.CredentialRules
import org.openhotspot.phone.hotspot.CredentialSource
import org.openhotspot.phone.hotspot.HotspotCredentials
import org.openhotspot.phone.hotspot.HotspotSettingsSource

/**
 * Hotspot settings. The user-entered passphrase is a secret, so the credentials are stored as one
 * Keystore-encrypted blob (same mechanism as the client keys, DECISIONS D23); the plain preferences
 * only hold the non-secret options.
 */
class HotspotPrefs(context: Context, private val cipher: SecretCipher, private val log: EngineLog = EngineLog.NONE) : HotspotSettingsSource {
    private val prefs = context.getSharedPreferences("hotspot_settings", Context.MODE_PRIVATE)

    var autoOffMinutesSetting: Int
        get() = prefs.getInt("auto_off_minutes", DEFAULT_AUTO_OFF_MINUTES)
        set(v) = prefs.edit { putInt("auto_off_minutes", v.coerceIn(0, MAX_AUTO_OFF_MINUTES)) }

    var allowLocalOnlySetting: Boolean
        get() = prefs.getBoolean("allow_local_only", true)
        set(v) = prefs.edit { putBoolean("allow_local_only", v) }

    override fun autoOffMinutes() = autoOffMinutesSetting
    override fun allowLocalOnly() = allowLocalOnlySetting

    override fun userCredentials(): HotspotCredentials? {
        val b64 = prefs.getString("user_credentials", null) ?: return null
        return try {
            decode(cipher.decrypt(Base64.decode(b64, Base64.NO_WRAP), AAD))
        } catch (e: Exception) {
            log.log(LogLevel.ERROR, "Stored hotspot credentials could not be read (${e.javaClass.simpleName}); ignoring them")
            null
        }
    }

    /** Returns an error message, or null when saved. */
    fun saveUserCredentials(ssid: String, passphrase: String, security: Security): String? {
        CredentialRules.validate(ssid, passphrase)?.let { return it }
        val creds = HotspotCredentials(ssid.toByteArray(Charsets.UTF_8), passphrase, security, Band.UNKNOWN, CredentialSource.USER)
        return try {
            val blob = Base64.encodeToString(cipher.encrypt(encode(creds), AAD), Base64.NO_WRAP)
            prefs.edit(commit = true) { putString("user_credentials", blob) } // synchronous: credentials must be durable
            null
        } catch (e: Exception) {
            "Could not store the credentials securely (${e.javaClass.simpleName})"
        }
    }

    fun clearUserCredentials() { prefs.edit { remove("user_credentials") } }

    private fun encode(c: HotspotCredentials): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeByte(1) // format version
            it.writeByte(c.security.code)
            it.writeByte(c.ssid.size); it.write(c.ssid)
            val p = c.passphrase.toByteArray(Charsets.UTF_8)
            it.writeByte(p.size); it.write(p)
        }
        return bos.toByteArray()
    }

    private fun decode(b: ByteArray): HotspotCredentials {
        DataInputStream(ByteArrayInputStream(b)).use {
            require(it.readUnsignedByte() == 1) { "unknown format" }
            val sec = Security.fromCode(it.readUnsignedByte()) ?: Security.WPA2_PSK
            val ssid = ByteArray(it.readUnsignedByte()).also { a -> it.readFully(a) }
            val pass = ByteArray(it.readUnsignedByte()).also { a -> it.readFully(a) }
            return HotspotCredentials(ssid, String(pass, Charsets.UTF_8), sec, Band.UNKNOWN, CredentialSource.USER)
        }
    }

    companion object {
        const val DEFAULT_AUTO_OFF_MINUTES = 10
        const val MAX_AUTO_OFF_MINUTES = 24 * 60
        private val AAD = "openhotspot.hotspot-credentials.v1".toByteArray()
    }
}
