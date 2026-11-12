package org.openhotspot.common.phone

import org.openhotspot.common.ErrorCode
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.Keys
import org.openhotspot.common.SessionKeys

/** Wall-clock and monotonic time, injectable for tests. */
interface Clock {
    fun wallMs(): Long
    fun monotonicMs(): Long
}

object SystemClock : Clock {
    override fun wallMs() = System.currentTimeMillis()
    override fun monotonicMs() = System.nanoTime() / 1_000_000
}

/** One paired client as the phone stores it. [ltk] is secret. */
class ClientRecord(
    val id: String,
    val ltk: ByteArray,
    val label: String,
    val pairedAtMs: Long,
    val lastCounter: Long,
) {
    val keys: SessionKeys by lazy { Keys.deriveSession(ltk) }
}

/**
 * Persistent client storage. Implementations MUST make [updateCounter] and [add] durable before
 * returning, because the engine acts on a request only after the counter is stored (SECURITY.md 5).
 */
interface ClientStore {
    fun list(): List<ClientRecord>
    fun add(record: ClientRecord)
    fun updateCounter(id: String, counter: Long)
    fun remove(id: String): Boolean
}

class InMemoryClientStore : ClientStore {
    private val items = LinkedHashMap<String, ClientRecord>()
    @Synchronized override fun list() = items.values.toList()
    @Synchronized override fun add(record: ClientRecord) { items[record.id] = record }
    @Synchronized override fun updateCounter(id: String, counter: Long) {
        items[id]?.let { items[id] = ClientRecord(it.id, it.ltk, it.label, it.pairedAtMs, counter) }
    }
    @Synchronized override fun remove(id: String) = items.remove(id) != null
}

/** Raised by a [HotspotController] to report a failure with the protocol error code to send back. */
class HotspotException(val code: ErrorCode, message: String = code.name) : Exception(message)

/**
 * The hotspot layer. Batch 3 ships only a fake; the Shizuku-backed implementation comes later.
 * Implementations throw [HotspotException] for failures (e.g. SHIZUKU_UNAVAILABLE).
 */
interface HotspotController {
    /** Whether the backend can currently act (Status bit 0). */
    fun isReady(): Boolean

    /** Whether the hotspot is currently on (Status bit 2). */
    fun isActive(): Boolean

    suspend fun enable(options: Int, autoOffSeconds: Int): HotspotInfo
    suspend fun disable()
    suspend fun query(): HotspotInfo
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Sink for human-readable events. Messages MUST NOT contain keys, SSIDs or passphrases. */
fun interface EngineLog {
    fun log(level: LogLevel, message: String)

    companion object {
        val NONE = EngineLog { _, _ -> }
    }
}
