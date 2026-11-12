package org.openhotspot.phone.hotspot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.HotspotState
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.HotspotController
import org.openhotspot.common.phone.HotspotException
import org.openhotspot.common.phone.LogLevel

/**
 * The real [HotspotController]: owns the backends, picks one by capability, falls back when it fails,
 * and runs the auto-off timer. All platform work is behind [HotspotBackend] and [WifiBinderApi].
 *
 * Selection: [refresh] probes the backends in order; [enable] tries the available ones in that order
 * until one starts. A failure of kind [FailureKind.NO_CREDENTIALS] stops the chain rather than falling
 * through to the no-internet local-only hotspot.
 */
class RealHotspotController(
    private val backends: List<HotspotBackend>,
    private val wifi: WifiBinderApi,
    private val settings: HotspotSettingsSource,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val log: EngineLog = EngineLog.NONE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val attemptTimeoutMs: Long = 9_000,
    private val reportMaxAgeMs: Long = 60_000,
) : HotspotController {
    private val selector = BackendSelector(backends, settings, clock, log)
    private val monitor = AutoOffMonitor(scope, clock, log)
    private val lock = Mutex()
    private val probeLock = Mutex()

    private val _report = MutableStateFlow<SelectionReport?>(null)
    private val _activeBackend = MutableStateFlow<BackendId?>(null)
    private val _lastError = MutableStateFlow<String?>(null)

    /** Result of the latest capability detection (null until the first [refresh]). */
    val report: StateFlow<SelectionReport?> get() = _report

    /** The backend that started the running hotspot, or null. */
    val activeBackend: StateFlow<BackendId?> get() = _activeBackend

    /** Last start/stop error in plain words, for the UI. Cleared by the next success. */
    val lastError: StateFlow<String?> get() = _lastError
    val autoOff: StateFlow<AutoOffState> get() = monitor.state

    @Volatile private var current: HotspotBackend? = null
    @Volatile private var lastCredentials: HotspotCredentials? = null

    /** Re-runs capability detection. Cheap and non-invasive; call when Shizuku or a permission changes. */
    suspend fun refresh(): SelectionReport = probeLock.withLock {
        selector.probeAll().also { _report.value = it }
    }

    override fun isReady(): Boolean = _report.value?.chosen != null

    override fun isActive(): Boolean = current?.active?.value ?: backends.any { it.active.value == true }

    override suspend fun enable(options: Int, autoOffSeconds: Int): HotspotInfo = enableOn(null, autoOffSeconds)

    /**
     * Like [enable], but restricted to [only] when non-null. Used by the diagnostics screen to test
     * each backend on its own; `null` is the normal automatic order including fallbacks.
     */
    suspend fun enableOn(only: BackendId?, autoOffSeconds: Int): HotspotInfo = lock.withLock {
        var report = _report.value
        if (report == null || report.chosen == null || clock.wallMs() - report.timeMs > reportMaxAgeMs) report = refresh()
        val candidates = backends.filter { b -> (only == null || b.id == only) && report.available.contains(b.id) }
        if (candidates.isEmpty()) {
            val why = report.entries.firstOrNull { it.result is ProbeResult.Unavailable && (only == null || it.id == only) }
            val kind = (why?.result as? ProbeResult.Unavailable)?.kind
            val msg = "No usable hotspot backend" + (only?.let { " ($it)" } ?: "") + ": " +
                report.entries.joinToString("; ") { "${it.id}: ${it.describe()}" }
            _lastError.value = msg
            log.log(LogLevel.ERROR, msg)
            throw HotspotException(kind?.errorCode ?: ErrorCode.HOTSPOT_FAILED, msg)
        }
        var first: BackendException? = null
        for (b in candidates) {
            try {
                val info = startOn(b, autoOffSeconds)
                _lastError.value = null
                return@withLock info
            } catch (e: BackendException) {
                if (first == null) first = e
                val msg = "${b.id} failed (${e.kind}): ${e.message}"
                _lastError.value = msg
                log.log(LogLevel.ERROR, msg)
                if (!e.kind.allowsFallback) break
                if (b !== candidates.last()) log.log(LogLevel.WARN, "Falling back to the next backend")
            }
        }
        val f = first!!
        throw HotspotException(f.kind.errorCode, f.message ?: f.kind.name)
    }

    private suspend fun startOn(b: HotspotBackend, requestedAutoOffSeconds: Int): HotspotInfo {
        val creds = chooseCredentials(b)
        if (b.credentialPolicy != CredentialPolicy.OWN && creds == null) {
            throw BackendException(
                FailureKind.NO_CREDENTIALS,
                "cannot read the phone's hotspot name and password; enter them under Settings > Hotspot credentials",
            )
        }
        log.log(LogLevel.INFO, "Starting hotspot via ${b.id}" + (creds?.let { " (credentials: ${it.source})" } ?: ""))
        if (!b.sharesInternet) log.log(LogLevel.WARN, "${b.id} has NO internet sharing; clients can connect but get no internet")
        val own = try {
            b.start(creds, attemptTimeoutMs)
        } catch (e: CancellationException) {
            // The protocol's 20 s limit expired (or the caller went away). Do not leave a hotspot nobody was told about.
            withContext(NonCancellable) { runCatching { b.stop() } }
            throw e
        } catch (e: BackendException) {
            if (e.kind == FailureKind.TIMEOUT || e.kind == FailureKind.START_FAILED) {
                withContext(NonCancellable) { runCatching { b.stop() } } // may have half-started
            }
            throw e
        }
        val finalCreds = own ?: creds!!
        current = b
        lastCredentials = finalCreds
        _activeBackend.value = b.id
        armAutoOff(b, requestedAutoOffSeconds)
        log.log(LogLevel.INFO, "Hotspot is on via ${b.id}")
        return finalCreds.toInfo(HotspotState.ON)
    }

    private fun armAutoOff(b: HotspotBackend, requestedSeconds: Int) {
        val seconds = AutoOffPolicy.effectiveSeconds(requestedSeconds, settings.autoOffMinutes())
        if (seconds == 0) {
            monitor.cancel()
            log.log(LogLevel.INFO, "Auto-off is not active (disabled in Settings and not requested)")
            return
        }
        monitor.start(b.clients, seconds * 1000L) {
            scope.launch {
                try { disable() } catch (e: Exception) { log.log(LogLevel.ERROR, "Auto-off could not turn the hotspot off: ${e.message}") }
            }
        }
    }

    /** Which credentials to report, per the backend's policy. Never logs them. */
    private suspend fun chooseCredentials(b: HotspotBackend): HotspotCredentials? {
        if (b.credentialPolicy == CredentialPolicy.OWN) return null
        val user = settings.userCredentials()?.withSource(CredentialSource.USER)
        val system = readSystem()
        return when (b.credentialPolicy) {
            CredentialPolicy.SYSTEM_FIRST -> system ?: user?.also {
                log.log(LogLevel.WARN, "Reporting the credentials typed in Settings; they must match the phone's hotspot settings or clients cannot join")
            }
            CredentialPolicy.USER_FIRST -> user ?: system
            CredentialPolicy.OWN -> null
        }
    }

    private suspend fun readSystem(): HotspotCredentials? {
        val r = withContext(io) { wifi.readSoftApConfig() }
        return when (r) {
            is ConfigRead.Found -> r.credentials
            is ConfigRead.Unavailable -> { log.log(LogLevel.WARN, "Could not read the phone's hotspot config: ${r.reason}"); null }
        }
    }

    override suspend fun disable() = lock.withLock {
        monitor.cancel()
        val targets = (listOfNotNull(current) + backends.filter { it.active.value == true }).distinct()
            .ifEmpty { listOfNotNull(backends.firstOrNull { it.id == _report.value?.chosen }) } // state unknown (for example after a restart): try the chosen one
        var failure: BackendException? = null
        for (b in targets) {
            try {
                b.stop()
                log.log(LogLevel.INFO, "Hotspot stopped via ${b.id}")
            } catch (e: BackendException) {
                failure = failure ?: e
                log.log(LogLevel.ERROR, "Stopping via ${b.id} failed (${e.kind}): ${e.message}")
            }
        }
        if (failure != null) {
            _lastError.value = "stop failed: ${failure.message}"
            throw HotspotException(failure.kind.errorCode, failure.message ?: failure.kind.name)
        }
        current = null
        lastCredentials = null
        _activeBackend.value = null
        _lastError.value = null
    }

    override suspend fun query(): HotspotInfo {
        if (!isActive()) return HotspotInfo(HotspotState.OFF)
        val creds = lastCredentials ?: (current ?: backends.firstOrNull { it.active.value == true })?.let { chooseCredentials(it) }
        if (creds == null) log.log(LogLevel.WARN, "Hotspot is on but its credentials are unknown")
        return creds?.toInfo(HotspotState.ON) ?: HotspotInfo(HotspotState.ON)
    }

    fun backendFor(id: BackendId?): HotspotBackend? = backends.firstOrNull { it.id == id }

    fun close() {
        monitor.cancel()
        backends.forEach { it.close() }
    }
}
