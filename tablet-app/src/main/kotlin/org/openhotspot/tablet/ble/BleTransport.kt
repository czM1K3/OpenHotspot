package org.openhotspot.tablet.ble

/** One advertisement that carried our service data. [serviceData] is the 9-byte payload (mode_ver and token). */
class Sighting(val address: String, val serviceData: ByteArray, val rssi: Int)

/**
 * The Bluetooth layer as the protocol client sees it. The real implementation ([GattTransport]) uses
 * platform APIs only; tests use an in-memory one wired to a real `PhoneEngine`.
 */
interface BleTransport {
    /**
     * Scans until [accept] maps a sighting to a non-null value, or [timeoutMs] passes (then null).
     * [accept] is called for every sighting and must be cheap.
     */
    suspend fun <T : Any> scan(timeoutMs: Long, accept: (Sighting) -> T?): T?

    /** Connects, negotiates the MTU, discovers the service and enables notifications. */
    suspend fun connect(address: String): BleLink
}

/** An open connection with notifications already enabled on the Response characteristic. */
interface BleLink {
    /** Negotiated ATT MTU (23 until the stack reports otherwise, PROTOCOL.md R13). */
    val mtu: Int

    /** Writes one chunk to the Request characteristic (Write With Response) and waits for the stack's ack. */
    suspend fun write(chunk: ByteArray)

    /** The next Response notification. Throws [BleException] (TIMEOUT or DISCONNECTED). */
    suspend fun receive(timeoutMs: Long): ByteArray

    fun close()
}

enum class BleProblem {
    /** Bluetooth is switched off or the adapter is missing. */
    BLUETOOTH_OFF,
    /** A runtime permission is missing. */
    PERMISSION,
    /** Android refused to scan (for example the 5-starts-in-30-seconds throttle, PROTOCOL.md R4). */
    SCAN_FAILED,
    CONNECT_FAILED,
    /** Connected, but the phone does not offer the OpenHotspot service (stale GATT cache, wrong device). */
    SERVICE_MISSING,
    /** The link dropped. */
    DISCONNECTED,
    TIMEOUT,
}

/** [retryable] means a whole new attempt (fresh scan, new connection, new counter) may succeed. */
class BleException(
    val problem: BleProblem,
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
