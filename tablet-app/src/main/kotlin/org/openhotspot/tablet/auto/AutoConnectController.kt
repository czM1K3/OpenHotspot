package org.openhotspot.tablet.auto

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.phone.Clock
import org.openhotspot.tablet.client.PhoneScanner
import org.openhotspot.tablet.data.AppSettings
import org.openhotspot.tablet.data.PhoneRecord
import org.openhotspot.tablet.data.PhoneStore
import org.openhotspot.tablet.data.SettingsStore
import org.openhotspot.tablet.flow.ConnectionState
import org.openhotspot.tablet.flow.Trigger

/** What the auto-trigger needs from the connection layer. [ConnectionController] is the real one. */
interface Connector {
    val state: StateFlow<ConnectionState>
    val busy: Boolean
    fun connect(phoneId: String?, trigger: Trigger): Boolean
}

/** Whether the tablet currently has working internet through some other network. */
interface NetworkMonitor {
    val online: StateFlow<Boolean>
}

/**
 * "No internet and a paired phone is nearby": connect by itself. Policy (mirrors the Linux daemon,
 * DECISIONS.md D35 and D41):
 *
 * - Does nothing unless the setting is on and at least one phone is paired.
 * - No scanning at all while online, while connecting, or while connected to the hotspot.
 * - Offline for [Timing.offlineDebounceMs] first (brief drops do not count), then short scan
 *   windows every [Timing.scanIntervalMs], doubling up to [Timing.maxScanIntervalMs] after
 *   [Timing.missesBeforeBackoff] empty windows.
 * - The cooldown starts BEFORE an attempt and nothing is attempted until it has passed, so neither a
 *   failure nor a crash can cause a retry storm. The same cooldown applies after success, so a
 *   hotspot the user just switched off is not switched on again at once.
 */
class AutoConnectController(
    private val settings: SettingsStore,
    private val store: PhoneStore,
    private val connection: Connector,
    private val client: PhoneScanner,
    private val monitor: NetworkMonitor,
    private val clock: Clock,
    private val timing: Timing = Timing(),
    /** False while Bluetooth is off or permissions are missing: nothing is scanned then. */
    private val canScan: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
) {
    data class Timing(
        val offlineDebounceMs: Long = 10_000,
        val scanDurationMs: Long = 6_000,
        val scanIntervalMs: Long = 60_000,
        val maxScanIntervalMs: Long = 300_000,
        val missesBeforeBackoff: Int = 5,
        /** Re-check interval while idle, so a missed change cannot stall us. */
        val pollMs: Long = 30_000,
    )

    private data class Inputs(val settings: AppSettings, val phones: List<PhoneRecord>, val online: Boolean, val connection: ConnectionState)

    private val inputs: Flow<Inputs> = combine(settings.settings, store.phones, monitor.online, connection.state, ::Inputs)

    private fun snapshot() = Inputs(settings.settings.value, store.phones.value, monitor.online.value, connection.state.value)

    private suspend fun idle(from: Inputs) {
        withTimeoutOrNull(timing.pollMs) { inputs.first { it != from } }
    }

    private var cooldownUntil: Long? = null // monotonic ms; null = no attempt yet
    private var misses = 0
    /** True once we have been offline for the debounce time; reset whenever we go quiet or online. */
    private var debounced = false

    /** Runs until cancelled. */
    suspend fun run() {
        while (true) {
            val now = snapshot()
            val active = now.settings.autoEnabled && now.phones.isNotEmpty()
            val quiet = now.online || connection.busy || now.connection is ConnectionState.Online
            if (!active || quiet) {
                misses = 0
                debounced = false
                idle(now)
                continue
            }
            val wait = cooldownUntil?.let { it - clock.monotonicMs() } ?: 0L
            if (wait > 0) {
                delay(minOf(wait, timing.pollMs))
                continue
            }
            if (!canScan()) {
                delay(timing.pollMs)
                continue
            }
            // Offline for a while before we spend any battery (once per offline period, not per scan).
            if (!debounced) {
                delay(timing.offlineDebounceMs)
                debounced = true
                if (monitor.online.value || !settings.settings.value.autoEnabled) continue
            }

            val found = try {
                client.findPhone(store.list(), timing.scanDurationMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Auto scan failed: ${e.javaClass.simpleName}")
                null
            }
            if (found == null) {
                misses++
                // Sleep, but wake early if the tablet gets online (then the back-off starts over).
                withTimeoutOrNull(scanInterval()) { monitor.online.first { it } }
                continue
            }
            misses = 0
            cooldownUntil = clock.monotonicMs() + settings.settings.value.cooldownSeconds * 1000L // before the attempt
            log("Paired phone nearby and no internet: connecting")
            if (connection.connect(found.phone.phoneId, Trigger.AUTO)) {
                connection.state.first { it !is ConnectionState.Working }
            }
        }
    }

    private fun scanInterval(): Long {
        if (misses < timing.missesBeforeBackoff) return timing.scanIntervalMs
        val shift = (misses - timing.missesBeforeBackoff + 1).coerceAtMost(10)
        return (timing.scanIntervalMs shl shift).coerceAtMost(timing.maxScanIntervalMs)
    }
}
