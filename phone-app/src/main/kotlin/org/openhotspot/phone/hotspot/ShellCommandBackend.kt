package org.openhotspot.phone.hotspot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openhotspot.common.Band
import org.openhotspot.common.Security
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.common.phone.SystemClock
import rikka.shizuku.Shizuku

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs a command with Shizuku's identity. The argument list is executed directly (no shell), so SSIDs and passphrases need no quoting. */
interface ShellRunner {
    suspend fun run(args: List<String>, timeoutMs: Long): ShellResult
}

/**
 * Runs commands through `Shizuku.newProcess`. That method is `private static` in API 13.1.5
 * (verified with javap), so it is reached by reflection; the supported alternative is a Shizuku
 * UserService, which needs an AIDL service and was judged too heavy for this batch (DECISIONS D28).
 */
class ShizukuShellRunner(private val io: CoroutineDispatcher = Dispatchers.IO) : ShellRunner {
    override suspend fun run(args: List<String>, timeoutMs: Long): ShellResult = withContext(io) {
        val process = ShizukuBinders.reflect("Shizuku.newProcess") {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            m.invoke(null, args.toTypedArray(), null, null) as Process
        }
        // ShizukuRemoteProcess.waitFor(timeout, unit) throws IllegalArgumentException("process hasn't exited")
        // on the device (found on Android 17, Shizuku API 14), so use the blocking waitFor() and enforce the
        // timeout with a watchdog that destroys the process, which unblocks the reads and the wait.
        var timedOut = false
        try {
            coroutineScope {
                val watchdog = launch { delay(timeoutMs); timedOut = true; process.destroy() }
                val out = async { process.inputStream.bufferedReader().readText() }
                val err = async { process.errorStream.bufferedReader().readText() }
                val stdout = try { out.await() } catch (e: java.io.IOException) { "" }
                val stderr = try { err.await() } catch (e: java.io.IOException) { "" }
                val code = try { process.waitFor() } catch (e: Exception) { if (timedOut) -1 else throw e }
                watchdog.cancel()
                if (timedOut) throw BackendException(FailureKind.TIMEOUT, "'${args.take(3).joinToString(" ")}' did not finish in $timeoutMs ms")
                ShellResult(code, stdout, stderr)
            }
        } finally {
            process.destroy()
        }
    }
}

/**
 * Fallback: `cmd wifi start-softap` / `stop-softap` run as the shell user.
 *
 * Verified against AOSP WifiShellCommand android11..17: the commands exist, with this syntax:
 *   `cmd wifi start-softap <ssid> (open|wpa2|wpa3|wpa3_transition|owe|owe_transition) <passphrase> [-b 2|5|6|any|bridged...]`
 *   `cmd wifi stop-softap`
 * (Android 11 supports only open|wpa2 and wraps the SSID in literal quotes, so this backend needs Android 12+.)
 *
 * Whether the AP then shares internet is the open question: every branch's help text says "the shell
 * command doesn't activate internet tethering". AOSP source suggests Android 12..15 get IP serving from the
 * Tethering module's WIFI_AP_STATE_CHANGED handler, Android 16 (android16-release) ignores that broadcast, and
 * the android17-release branch routes the command through TetheringManager.startTethering. NONE OF THIS IS VERIFIED ON A DEVICE.
 */
class ShellCommandBackend(
    private val gateway: ShizukuGateway,
    private val shell: ShellRunner,
    private val wifi: WifiBinderApi,
    private val sdkInt: Int,
    private val log: EngineLog = EngineLog.NONE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMs: Long = 300,
    private val clock: Clock = SystemClock,
) : HotspotBackend {
    override val id = BackendId.SHELL_COMMAND
    override val sharesInternet = true // intended; see class comment
    override val credentialPolicy = CredentialPolicy.USER_FIRST

    private val _active = MutableStateFlow<Boolean?>(null)
    override val active: StateFlow<Boolean?> get() = _active

    /** No shell command lists connected clients, so this backend cannot support auto-off (documented). */
    override val clients: StateFlow<Int?> = MutableStateFlow(null)

    override suspend fun probe(): ProbeResult {
        shizukuProblem(gateway.status.value)?.let { return it }
        if (sdkInt < 31) {
            return ProbeResult.Unavailable(FailureKind.UNSUPPORTED, "needs Android 12+ (Android 11's start-softap only supports open|wpa2 and quotes the SSID)")
        }
        return try {
            val r = shell.run(listOf("cmd", "wifi", "help"), 8_000)
            when {
                r.exitCode != 0 -> ProbeResult.Unavailable(FailureKind.API_UNAVAILABLE, "'cmd wifi help' exited ${r.exitCode}: ${Redactor.redact(r.stderr.ifBlank { r.stdout }, emptyList(), 160)}")
                !r.stdout.contains("start-softap") -> ProbeResult.Unavailable(FailureKind.UNSUPPORTED, "'cmd wifi help' does not list start-softap")
                else -> ProbeResult.Available(
                    "cmd wifi lists start-softap" + (if (supportsWpa3(r.stdout)) " (wpa3 supported)" else " (wpa2 only)") +
                        "; may not share internet on every Android version",
                )
            }
        } catch (e: BackendException) {
            ProbeResult.Unavailable(e.kind, e.message ?: e.kind.name)
        } catch (e: Exception) {
            ProbeResult.Unavailable(FailureKind.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override suspend fun start(credentials: HotspotCredentials?, timeoutMs: Long): HotspotCredentials? {
        requireShizuku(gateway.status.value)
        if (credentials == null) throw BackendException(FailureKind.NO_CREDENTIALS, "start-softap needs an SSID and passphrase; enter them in Settings")
        val args = ShellCommands.startSoftAp(credentials, sdkInt)
        val secrets = listOf(String(credentials.ssid, Charsets.UTF_8), credentials.passphrase)
        log.log(LogLevel.INFO, "Running: cmd wifi start-softap <ssid> ${args[4]} <passphrase>" + args.drop(6).joinToString("") { " $it" })
        val r = shell.run(args, timeoutMs.coerceAtMost(15_000))
        val out = Redactor.redact(r.stdout + " " + r.stderr, secrets)
        log.log(LogLevel.INFO, "start-softap exit=${r.exitCode}: $out")
        when (ShellCommands.parseStart(r)) {
            ShellCommands.Outcome.FAILED -> throw BackendException(FailureKind.START_FAILED, "start-softap failed: $out")
            ShellCommands.Outcome.SUCCEEDED -> Unit
            ShellCommands.Outcome.UNKNOWN -> log.log(LogLevel.WARN, "start-softap printed nothing we recognise; checking the AP state instead")
        }
        if (!awaitEnabled(true, 8_000)) {
            throw BackendException(FailureKind.START_FAILED, "the Wi-Fi service does not report the AP as enabled after start-softap")
        }
        _active.value = true
        return null
    }

    override suspend fun stop() {
        requireShizuku(gateway.status.value)
        val r = shell.run(ShellCommands.STOP_SOFTAP, 10_000)
        log.log(LogLevel.INFO, "stop-softap exit=${r.exitCode}: ${Redactor.redact(r.stdout + " " + r.stderr, emptyList())}")
        if (ShellCommands.parseStop(r) == ShellCommands.Outcome.FAILED && wifi.apState() == SoftApMapping.WIFI_AP_STATE_ENABLED) {
            throw BackendException(FailureKind.START_FAILED, "stop-softap reported failure and the AP is still enabled")
        }
        _active.value = false
    }

    /** Polls the Wi-Fi service. If it cannot be read at all, the shell output alone has to be trusted (returns true). */
    private suspend fun awaitEnabled(enabled: Boolean, timeoutMs: Long): Boolean {
        val deadline = clock.monotonicMs() + timeoutMs
        while (true) {
            val state = withContext(io) { wifi.apState() } ?: return true
            if ((state == SoftApMapping.WIFI_AP_STATE_ENABLED) == enabled) return true
            if (clock.monotonicMs() >= deadline) return false
            delay(pollMs)
        }
    }

    private fun supportsWpa3(help: String) = Regex("start-softap[^\\n]*wpa3").containsMatchIn(help)
}

/** Pure command construction and output parsing (unit-tested). */
object ShellCommands {
    val STOP_SOFTAP = listOf("cmd", "wifi", "stop-softap")

    enum class Outcome { SUCCEEDED, FAILED, UNKNOWN }

    fun startSoftAp(c: HotspotCredentials, sdkInt: Int): List<String> {
        val type = when (c.security) {
            Security.WPA3_SAE -> if (sdkInt >= 31) "wpa3" else "wpa2"
            Security.WPA2_WPA3 -> if (sdkInt >= 31) "wpa3_transition" else "wpa2"
            else -> "wpa2"
        }
        val band = when (c.band) {
            Band.GHZ_2_4 -> listOf("-b", "2")
            Band.GHZ_5 -> listOf("-b", "5")
            Band.GHZ_6 -> listOf("-b", "6")
            Band.UNKNOWN -> emptyList()
        }
        return listOf("cmd", "wifi", "start-softap", String(c.ssid, Charsets.UTF_8), type, c.passphrase) + band
    }

    /**
     * Output strings, from WifiShellCommand: Android 12+ waits for the SoftAp callback and prints
     * " SAP is enabled successfully" or " SAP failed to start"; a rejected config prints
     * "Soft AP failed to start. Please check config parameters".
     */
    fun parseStart(r: ShellResult): Outcome {
        val t = (r.stdout + "\n" + r.stderr).lowercase()
        return when {
            r.exitCode != 0 -> Outcome.FAILED
            "failed" in t -> Outcome.FAILED
            "enabled successfully" in t || "started successfully" in t -> Outcome.SUCCEEDED
            else -> Outcome.UNKNOWN
        }
    }

    fun parseStop(r: ShellResult): Outcome {
        val t = (r.stdout + "\n" + r.stderr).lowercase()
        return when {
            r.exitCode != 0 || "failed" in t -> Outcome.FAILED
            "stopped successfully" in t -> Outcome.SUCCEEDED
            else -> Outcome.UNKNOWN
        }
    }
}
