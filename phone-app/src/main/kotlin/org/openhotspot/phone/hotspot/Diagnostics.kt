package org.openhotspot.phone.hotspot

import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openhotspot.common.HotspotState
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.phone.data.LogEntry

data class DiagStep(val name: String, val ok: Boolean, val ms: Long, val detail: String)

data class DiagRun(val startedMs: Long, val only: BackendId?, val steps: List<DiagStep>) {
    val ok: Boolean get() = steps.isNotEmpty() && steps.all { it.ok }
}

/**
 * The "Test hotspot toggle" sequence: detect, enable, query, hold, disable, verify off. It goes through
 * [RealHotspotController] exactly like a BLE request does, so a pass here means the real path works.
 * Step details never contain the SSID or passphrase, only their lengths.
 */
class DiagnosticsRunner(
    private val controller: RealHotspotController,
    private val clock: Clock,
    private val log: EngineLog = EngineLog.NONE,
    private val holdMs: Long = 30_000,
    private val offTimeoutMs: Long = 8_000,
) {
    private val _running = MutableStateFlow(false)
    private val _last = MutableStateFlow<DiagRun?>(null)
    val running: StateFlow<Boolean> get() = _running
    val last: StateFlow<DiagRun?> get() = _last

    /** [only] restricts the test to one backend (no fallback); null tests the automatic order. */
    suspend fun run(only: BackendId?) {
        if (!_running.compareAndSet(expect = false, update = true)) return
        val steps = mutableListOf<DiagStep>()
        val started = clock.wallMs()
        fun publish() { _last.value = DiagRun(started, only, steps.toList()) }
        suspend fun step(name: String, block: suspend () -> Pair<Boolean, String>): Boolean {
            val t0 = clock.monotonicMs()
            val (ok, detail) = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false to "${e.javaClass.simpleName}: ${e.message}"
            }
            steps += DiagStep(name, ok, clock.monotonicMs() - t0, detail)
            log.log(if (ok) LogLevel.INFO else LogLevel.ERROR, "Diagnostics '$name': ${if (ok) "OK" else "FAILED"} - $detail")
            publish()
            return ok
        }
        try {
            log.log(LogLevel.INFO, "Diagnostics: toggle test started (${only ?: "automatic order"})")
            step("Detect backends") {
                val r = controller.refresh()
                (r.chosen != null) to r.entries.joinToString("; ") { "${it.id}: ${it.describe()}" }
            }
            val enabled = step("Enable hotspot") {
                val info = controller.enableOn(only, autoOffSeconds = 0)
                (info.state == HotspotState.ON) to "via ${controller.activeBackend.value}; state=${info.state}; security=${info.security}; band=${info.band}; " +
                    "ssid=${info.ssid.size} bytes; passphrase=${info.passphrase.toByteArray().size} bytes"
            }
            if (enabled) {
                step("Query") {
                    val q = controller.query()
                    (q.state == HotspotState.ON && q.ssid.isNotEmpty()) to "state=${q.state}; ssid=${q.ssid.size} bytes; isActive=${controller.isActive()}"
                }
                step("Hold ${holdMs / 1000} s (join it from another device now to test the client count)") {
                    delay(holdMs)
                    val clients = controller.activeBackendClients()
                    controller.isActive() to "still active=${controller.isActive()}; clients=${clients ?: "unknown"}"
                }
            }
            // Only after a successful enable: a failed enable is already cleaned up by the controller, and a
            // blind disable here would switch off a hotspot the user had on for other reasons.
            if (enabled) {
                step("Disable hotspot") { controller.disable(); true to "stopped" }
                step("Verify off") {
                    val deadline = clock.monotonicMs() + offTimeoutMs
                    while (controller.isActive() && clock.monotonicMs() < deadline) delay(300)
                    (!controller.isActive()) to "isActive=${controller.isActive()}"
                }
            }
        } finally {
            _running.value = false
            publish()
        }
    }
}

/** Convenience for the diagnostics hold step. */
fun RealHotspotController.activeBackendClients(): Int? = backendFor(activeBackend.value)?.clients?.value

data class DeviceInfo(
    val manufacturer: String, val model: String, val device: String, val release: String, val sdk: Int,
    val securityPatch: String, val buildDisplay: String, val appVersion: String,
) {
    companion object {
        fun current(appVersion: String) = DeviceInfo(
            Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            Build.VERSION.SECURITY_PATCH, Build.DISPLAY, appVersion,
        )
    }
}

/** Builds the text the user pastes back. Pure, so it is unit-tested. Contains no SSID or passphrase. */
object DiagnosticsReport {
    fun format(
        nowMs: Long, device: DeviceInfo, shizuku: ShizukuStatus, report: SelectionReport?,
        settingsSummary: String, run: DiagRun?, log: List<LogEntry>, timeFmt: (Long) -> String = { it.toString() },
    ): String = buildString {
        appendLine("OpenHotspot diagnostics ${timeFmt(nowMs)}")
        appendLine("App ${device.appVersion}")
        appendLine("Device: ${device.manufacturer} ${device.model} (${device.device})")
        appendLine("Android ${device.release} (API ${device.sdk}), security patch ${device.securityPatch}")
        appendLine("Build: ${device.buildDisplay}")
        appendLine()
        appendLine("Shizuku: ${shizuku.state} (${shizuku.state.label}); server uid=${shizuku.serverUid ?: "-"}; API version=${shizuku.serverVersion ?: "-"}; permanentlyDenied=${shizuku.permanentlyDenied}")
        appendLine("Settings: $settingsSummary")
        appendLine()
        appendLine("Capability detection:")
        if (report == null) appendLine("  (not run yet)") else {
            for (e in report.entries) appendLine("  ${e.id} [${e.tookMs} ms] ${e.describe()}")
            appendLine("  => selected: ${report.chosen ?: "NONE"}")
        }
        appendLine()
        appendLine("Last toggle test:")
        if (run == null) appendLine("  (not run yet)") else {
            appendLine("  started ${timeFmt(run.startedMs)}, target ${run.only ?: "automatic"}, overall ${if (run.ok) "PASS" else "FAIL"}")
            for (s in run.steps) appendLine("  [${if (s.ok) "ok" else "FAIL"}] ${s.name} (${s.ms} ms): ${s.detail}")
        }
        appendLine()
        appendLine("Log (oldest first, ${log.size} entries):")
        for (e in log) appendLine("  ${timeFmt(e.timeMs)} ${e.level.name.first()} ${e.message}")
    }
}
