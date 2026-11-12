package org.openhotspot.phone.hotspot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openhotspot.common.Band
import org.openhotspot.common.Security
import org.openhotspot.common.phone.Clock
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.LogLevel

/** Clock the tests advance by hand, or tie to a TestScope scheduler. */
class TestClock(var wall: Long = 1_000_000, private val mono: () -> Long = { 0 }) : Clock {
    override fun wallMs() = wall + mono()
    override fun monotonicMs() = mono()
}

class RecordingLog : EngineLog {
    val lines = mutableListOf<String>()
    override fun log(level: LogLevel, message: String) { lines += "$level $message" }
    fun text() = lines.joinToString("\n")
}

fun creds(
    ssid: String = "MyPhone", pass: String = "correct-horse", security: Security = Security.WPA2_PSK,
    band: Band = Band.GHZ_5, source: CredentialSource = CredentialSource.USER,
) = HotspotCredentials(ssid.toByteArray(), pass, security, band, source)

class FakeWifi(var config: ConfigRead = ConfigRead.Found(creds(source = CredentialSource.SYSTEM)), var state: Int? = null) : WifiBinderApi {
    var reads = 0
    override fun readSoftApConfig(): ConfigRead { reads++; return config }
    override fun apState(): Int? = state
}

class FakeGateway(initial: ShizukuStatus = ShizukuStatus(ShizukuState.READY, serverUid = 2000, serverVersion = 13)) : ShizukuGateway {
    val flow = MutableStateFlow(initial)
    override val status: StateFlow<ShizukuStatus> get() = flow
    var requested = 0
    override fun refresh() {}
    override fun requestPermission() { requested++ }
    override fun openManager() = false
}

/** A backend whose behaviour is set per test. */
class StubBackend(
    override val id: BackendId,
    override val sharesInternet: Boolean = id != BackendId.LOCAL_ONLY,
    override val credentialPolicy: CredentialPolicy = when (id) {
        BackendId.SHIZUKU_TETHERING -> CredentialPolicy.SYSTEM_FIRST
        BackendId.SHELL_COMMAND -> CredentialPolicy.USER_FIRST
        BackendId.LOCAL_ONLY -> CredentialPolicy.OWN
    },
    var probeResult: ProbeResult = ProbeResult.Available("fake"),
    var onStart: suspend (HotspotCredentials?) -> HotspotCredentials? = { null },
) : HotspotBackend {
    val calls = mutableListOf<String>()
    val activeFlow = MutableStateFlow<Boolean?>(false)
    val clientsFlow = MutableStateFlow<Int?>(0)
    var startedWith: HotspotCredentials? = null
    var stopFailure: BackendException? = null
    var probeThrows: Throwable? = null
    override val active: StateFlow<Boolean?> get() = activeFlow
    override val clients: StateFlow<Int?> get() = clientsFlow

    override suspend fun probe(): ProbeResult { calls += "probe"; probeThrows?.let { throw it }; return probeResult }

    override suspend fun start(credentials: HotspotCredentials?, timeoutMs: Long): HotspotCredentials? {
        calls += "start"
        startedWith = credentials
        val own = onStart(credentials)
        activeFlow.value = true
        return own
    }

    override suspend fun stop() { calls += "stop"; stopFailure?.let { throw it }; activeFlow.value = false }
}

class FakeSettings(
    var user: HotspotCredentials? = null, var autoOff: Int = 0, var localOnly: Boolean = true,
) : HotspotSettingsSource {
    override fun userCredentials() = user
    override fun autoOffMinutes() = autoOff
    override fun allowLocalOnly() = localOnly
}

fun unavailable(kind: FailureKind, reason: String = "nope") = ProbeResult.Unavailable(kind, reason)
