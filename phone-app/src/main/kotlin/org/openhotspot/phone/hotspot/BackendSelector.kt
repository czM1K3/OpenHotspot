package org.openhotspot.phone.hotspot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel

/** User-adjustable hotspot settings, as the controller sees them. */
interface HotspotSettingsSource {
    /** The SSID and passphrase the user typed in Settings, if any. */
    fun userCredentials(): HotspotCredentials?

    /** Minutes without connected clients before the hotspot is turned off; 0 = never. */
    fun autoOffMinutes(): Int

    /** Whether the no-internet local-only fallback may be used at all. */
    fun allowLocalOnly(): Boolean
}

/** One backend's probe outcome. Exactly one of [result] and [skipped] is set. */
data class ProbeEntry(val id: BackendId, val result: ProbeResult?, val skipped: String?, val tookMs: Long) {
    val available: Boolean get() = result is ProbeResult.Available

    fun describe(): String = when {
        skipped != null -> "skipped: $skipped"
        result is ProbeResult.Available -> "available: ${result.detail}"
        result is ProbeResult.Unavailable -> "unavailable (${result.kind}): ${result.reason}"
        else -> "?"
    }
}

/** The capability detection result shown in the UI and the diagnostics export. */
data class SelectionReport(val entries: List<ProbeEntry>, val timeMs: Long) {
    val chosen: BackendId? get() = entries.firstOrNull { it.available }?.id
    val available: List<BackendId> get() = entries.filter { it.available }.map { it.id }
}

/**
 * Probes the backends in priority order and records what each can do. The first available backend is
 * the choice; the rest stay recorded as fallbacks for [RealHotspotController].
 */
class BackendSelector(
    private val backends: List<HotspotBackend>,
    private val settings: HotspotSettingsSource,
    private val clock: Clock,
    private val log: EngineLog = EngineLog.NONE,
    private val probeTimeoutMs: Long = 10_000,
) {
    suspend fun probeAll(): SelectionReport {
        val entries = backends.map { b ->
            if (b.id == BackendId.LOCAL_ONLY && !settings.allowLocalOnly()) {
                ProbeEntry(b.id, null, "disabled in Settings", 0)
            } else {
                val t0 = clock.monotonicMs()
                val r = try {
                    withTimeoutOrNull(probeTimeoutMs) { b.probe() }
                        ?: ProbeResult.Unavailable(FailureKind.TIMEOUT, "probe took longer than $probeTimeoutMs ms")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProbeResult.Unavailable(FailureKind.INTERNAL, "probe threw ${e.javaClass.simpleName}: ${e.message}")
                }
                ProbeEntry(b.id, r, null, clock.monotonicMs() - t0)
            }
        }
        val report = SelectionReport(entries, clock.wallMs())
        for (e in entries) log.log(if (e.available) LogLevel.INFO else LogLevel.WARN, "Backend ${e.id}: ${e.describe()}")
        log.log(LogLevel.INFO, "Selected backend: ${report.chosen ?: "NONE"}")
        return report
    }
}
