package org.openhotspot.common

/** Constants from docs/PROTOCOL.md (version 1). Keep in sync with the spec. */
object Proto {
    const val VERSION = 1

    const val SERVICE_UUID = "7e5f0001-8c3a-4d2b-9a61-0b4c7d9e1f20"
    const val CHAR_REQUEST_UUID = "7e5f0002-8c3a-4d2b-9a61-0b4c7d9e1f20"
    const val CHAR_RESPONSE_UUID = "7e5f0003-8c3a-4d2b-9a61-0b4c7d9e1f20"
    const val CHAR_STATUS_UUID = "7e5f0004-8c3a-4d2b-9a61-0b4c7d9e1f20"
    /** Standard Client Characteristic Configuration descriptor. */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    val SERVICE_UUID_BYTES: ByteArray = uuidToBytes(SERVICE_UUID)

    const val HEADER_LEN = 5
    const val FRAME_HEADER_LEN = 7
    const val MAX_FRAME = 512
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val MIN_MTU = 23

    const val FLAG_PLAINTEXT = 0x01

    const val ADV_WINDOW_S = 300L
    const val ADV_TOLERANCE_WINDOWS = 1
    const val ADV_MODE_PAIRED = 0
    const val ADV_MODE_PAIRING = 1
    val ADV_LABEL_PAIRED = "OHS1 adv".toByteArray(Charsets.US_ASCII)
    val ADV_LABEL_PAIRING = "OHS1 adv-pair".toByteArray(Charsets.US_ASCII)

    const val TIMESTAMP_SKEW_MS = 60_000L
    const val PAIRING_WINDOW_MS = 120_000L
    const val PAIRING_MAX_FAILURES = 3
    const val MAX_CLIENTS = 8
    const val MAX_LABEL_BYTES = 32
    const val REASSEMBLY_TIMEOUT_MS = 5_000L
    const val HOTSPOT_TIMEOUT_MS = 20_000L

    const val QR_PREFIX = "openhotspot://pair/"
    const val QR_RAW_LEN = 65
}

enum class MsgType(val code: Int) {
    PAIR_REQ(0x01), PAIR_RESP(0x81),
    ENABLE(0x10), ENABLE_RESP(0x90),
    DISABLE(0x11), DISABLE_RESP(0x91),
    QUERY(0x12), QUERY_RESP(0x92),
    UNPAIR(0x13), UNPAIR_RESP(0x93),
    ERROR(0xFF);

    val isRequest: Boolean get() = this == ENABLE || this == DISABLE || this == QUERY || this == UNPAIR

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): MsgType? = byCode[code]
    }
}

enum class ErrorCode(val code: Int) {
    MALFORMED(0x01), UNSUPPORTED_VERSION(0x02), AUTH_FAILED(0x03), RATE_LIMITED(0x04), REPLAY(0x05),
    STALE_TIMESTAMP(0x06), NOT_IN_PAIRING_MODE(0x07), BUSY(0x08), TOO_MANY_CLIENTS(0x09),
    SHIZUKU_UNAVAILABLE(0x10), PERMISSION_DENIED(0x11), HOTSPOT_FAILED(0x12), UNSUPPORTED_REQUEST(0x13),
    INTERNAL(0xFF);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): ErrorCode? = byCode[code]
    }
}

enum class HotspotState(val code: Int) {
    OFF(0), STARTING(1), ON(2);

    companion object {
        fun fromCode(code: Int): HotspotState? = entries.firstOrNull { it.code == code }
    }
}

enum class Security(val code: Int) {
    OPEN(0), WPA2_PSK(1), WPA3_SAE(2), WPA2_WPA3(3);

    companion object {
        fun fromCode(code: Int): Security? = entries.firstOrNull { it.code == code }
    }
}

enum class Band(val code: Int) {
    UNKNOWN(0), GHZ_2_4(1), GHZ_5(2), GHZ_6(3);

    companion object {
        fun fromCode(code: Int): Band? = entries.firstOrNull { it.code == code }
    }
}
