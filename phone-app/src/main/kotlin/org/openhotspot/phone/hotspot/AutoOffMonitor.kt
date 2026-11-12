package org.openhotspot.phone.hotspot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel

sealed interface AutoOffState {
    data object Off : AutoOffState

    /** The backend cannot count clients, so no timer runs. */
    data object ClientCountUnknown : AutoOffState
    data class ClientsConnected(val count: Int) : AutoOffState
    data class Countdown(val deadlineWallMs: Long) : AutoOffState
}

/** Computes the idle timeout from the client's ENABLE request and the user setting. */
object AutoOffPolicy {
    const val MIN_SECONDS = 60
    const val MAX_SECONDS = 24 * 3600

    /**
     * PROTOCOL.md section 5: `auto_off_s` of 0 means the phone default and the phone MAY clamp.
     * When both a request and a setting apply, the stricter (shorter) one wins, so a client can
     * shorten the timeout but never extend what the owner configured. Returns seconds, 0 = no auto-off.
     */
    fun effectiveSeconds(requestedSeconds: Int, settingMinutes: Int): Int {
        val fromRequest = if (requestedSeconds > 0) requestedSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS) else 0
        val fromSetting = if (settingMinutes > 0) (settingMinutes.toLong() * 60).coerceAtMost(MAX_SECONDS.toLong()).toInt() else 0
        return when {
            fromRequest > 0 && fromSetting > 0 -> minOf(fromRequest, fromSetting)
            fromRequest > 0 -> fromRequest
            else -> fromSetting
        }
    }
}

/**
 * Watches a client count and fires [onIdle] once the count has stayed at 0 for the whole idle period.
 * A count of null (unknown) never fires, because turning the hotspot off under connected clients is worse than leaving it on.
 */
class AutoOffMonitor(private val scope: CoroutineScope, private val clock: Clock, private val log: EngineLog = EngineLog.NONE) {
    private val _state = MutableStateFlow<AutoOffState>(AutoOffState.Off)
    val state: StateFlow<AutoOffState> get() = _state
    private var job: Job? = null

    @Synchronized
    fun start(clients: StateFlow<Int?>, idleMs: Long, onIdle: suspend () -> Unit) {
        cancel()
        job = scope.launch {
            clients.collectLatest { n ->
                when {
                    n == null -> {
                        _state.value = AutoOffState.ClientCountUnknown
                        log.log(LogLevel.WARN, "Auto-off disabled: this backend cannot count connected clients")
                    }
                    n > 0 -> {
                        _state.value = AutoOffState.ClientsConnected(n)
                        log.log(LogLevel.INFO, "Clients connected: $n; auto-off timer paused")
                    }
                    else -> {
                        _state.value = AutoOffState.Countdown(clock.wallMs() + idleMs)
                        log.log(LogLevel.INFO, "No clients; hotspot turns off in ${idleMs / 1000} s unless one connects")
                        delay(idleMs)
                        log.log(LogLevel.INFO, "Auto-off: no clients for ${idleMs / 1000} s")
                        _state.value = AutoOffState.Off
                        onIdle()
                    }
                }
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = AutoOffState.Off
    }
}
