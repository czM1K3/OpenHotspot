package org.openhotspot.common

import org.openhotspot.common.crypto.Crypto

/** Advertisement tokens and recognition (PROTOCOL.md section 3). */
object Advertisement {
    const val TOKEN_LEN = 8

    class Payload(val version: Int, val mode: Int, val token: ByteArray)

    fun windowIndex(unixSeconds: Long): Long = unixSeconds / Proto.ADV_WINDOW_S

    fun makeToken(key: ByteArray, label: ByteArray, window: Long): ByteArray =
        Crypto.hmacSha256(key, label + u64(window)).copyOf(TOKEN_LEN)

    fun pairedToken(kAdv: ByteArray, unixSeconds: Long) = makeToken(kAdv, Proto.ADV_LABEL_PAIRED, windowIndex(unixSeconds))

    fun pairingToken(pairingSecret: ByteArray, unixSeconds: Long) =
        makeToken(pairingSecret, Proto.ADV_LABEL_PAIRING, windowIndex(unixSeconds))

    /** The 9-byte service-data payload: `mode_ver(1) | token(8)`. */
    fun buildPayload(mode: Int, token: ByteArray): ByteArray = byteArrayOf(((Proto.VERSION shl 4) or mode).toByte()) + token

    /** The full AD structure (Service Data, 128-bit UUID). BLE sends the UUID fully byte-reversed. */
    fun buildAdStructure(mode: Int, token: ByteArray): ByteArray {
        val payload = buildPayload(mode, token)
        return byteArrayOf((1 + 16 + payload.size).toByte(), 0x21) + Proto.SERVICE_UUID_BYTES.reversedArray() + payload
    }

    fun parsePayload(serviceData: ByteArray): Payload? {
        if (serviceData.size != 1 + TOKEN_LEN || serviceData.u8(0) shr 4 != Proto.VERSION) return null
        val mode = serviceData.u8(0) and 0x0F
        if (mode != Proto.ADV_MODE_PAIRED && mode != Proto.ADV_MODE_PAIRING) return null
        return Payload(Proto.VERSION, mode, serviceData.copyOfRange(1, serviceData.size))
    }

    private fun matches(serviceData: ByteArray, mode: Int, key: ByteArray, label: ByteArray, unixSeconds: Long): Boolean {
        val p = parsePayload(serviceData) ?: return false
        if (p.mode != mode) return false
        val w = windowIndex(unixSeconds)
        return (-Proto.ADV_TOLERANCE_WINDOWS..Proto.ADV_TOLERANCE_WINDOWS).any {
            constantTimeEquals(p.token, makeToken(key, label, w + it))
        }
    }

    /** True if the payload is a paired-mode token for [kAdv] (windows w-1, w, w+1). A hint only. */
    fun matchesPaired(serviceData: ByteArray, kAdv: ByteArray, unixSeconds: Long) =
        matches(serviceData, Proto.ADV_MODE_PAIRED, kAdv, Proto.ADV_LABEL_PAIRED, unixSeconds)

    fun matchesPairing(serviceData: ByteArray, pairingSecret: ByteArray, unixSeconds: Long) =
        matches(serviceData, Proto.ADV_MODE_PAIRING, pairingSecret, Proto.ADV_LABEL_PAIRING, unixSeconds)
}
