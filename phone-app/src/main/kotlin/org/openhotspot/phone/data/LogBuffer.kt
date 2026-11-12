package org.openhotspot.phone.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel

data class LogEntry(val id: Long, val timeMs: Long, val level: LogLevel, val message: String)

/**
 * In-memory ring buffer behind the log screen. It is cleared when the process dies, by design:
 * nothing sensitive is ever written here (callers must not pass keys, SSIDs or passphrases), and
 * nothing is persisted to disk.
 */
class LogBuffer(
    private val capacity: Int = 500,
    private val now: () -> Long = System::currentTimeMillis,
    private val mirrorToLogcat: Boolean = true,
) : EngineLog {
    private var nextId = 0L
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Oldest first. */
    val entries: StateFlow<List<LogEntry>> get() = _entries

    override fun log(level: LogLevel, message: String) = add(level, message)

    @Synchronized
    fun add(level: LogLevel, message: String) {
        val entry = LogEntry(nextId++, now(), level, message)
        _entries.update { (it + entry).takeLast(capacity) }
        if (mirrorToLogcat) {
            val priority = when (level) {
                LogLevel.DEBUG -> Log.DEBUG; LogLevel.INFO -> Log.INFO; LogLevel.WARN -> Log.WARN; LogLevel.ERROR -> Log.ERROR
            }
            Log.println(priority, "OpenHotspot", message)
        }
    }

    fun clear() { _entries.value = emptyList() }
}
