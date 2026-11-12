package org.openhotspot.phone.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.openhotspot.common.Band
import org.openhotspot.common.Security

@OptIn(ExperimentalCoroutinesApi::class)
class ShellCommandBackendTest {
    private class FakeShell(var handler: (List<String>) -> ShellResult) : ShellRunner {
        val commands = mutableListOf<List<String>>()
        override suspend fun run(args: List<String>, timeoutMs: Long): ShellResult { commands += args; return handler(args) }
    }

    private val help12 = "  start-softap <ssid> (open|wpa2|wpa3|wpa3_transition) <passphrase> [-b 2|5|6|any]\n    Start softap\n  stop-softap\n"
    private val help11 = "  start-softap <ssid> (open|wpa2) <passphrase> [-b 2|5|6|any]\n  stop-softap\n"

    private fun TestScope.make(shell: FakeShell, sdk: Int = 36, gateway: FakeGateway = FakeGateway(), wifi: FakeWifi = FakeWifi(), log: RecordingLog = RecordingLog()) =
        ShellCommandBackend(gateway, shell, wifi, sdk, log, io = Dispatchers.Unconfined, clock = TestClock(mono = { testScheduler.currentTime }))

    @Test fun startSoftApCommandKeepsEveryArgumentIntact() {
        val args = ShellCommands.startSoftAp(creds("My Phone \"5G\" ; rm -rf /", "pa ss;word'-1", Security.WPA2_PSK, Band.GHZ_5), 34)
        // The list is executed without a shell, so spaces, quotes and metacharacters must stay in one argument each.
        assertEquals(listOf("cmd", "wifi", "start-softap", "My Phone \"5G\" ; rm -rf /", "wpa2", "pa ss;word'-1", "-b", "5"), args)
    }

    @Test fun securityAndBandMapping() {
        fun type(s: Security, sdk: Int) = ShellCommands.startSoftAp(creds(security = s, band = Band.UNKNOWN), sdk)[4]
        assertEquals("wpa2", type(Security.WPA2_PSK, 34))
        assertEquals("wpa3", type(Security.WPA3_SAE, 34))
        assertEquals("wpa3_transition", type(Security.WPA2_WPA3, 34))
        assertEquals("wpa2", type(Security.WPA3_SAE, 30), "wpa3 is not accepted before Android 12")
        assertEquals(8, ShellCommands.startSoftAp(creds(band = Band.GHZ_2_4), 34).size)
        assertEquals(6, ShellCommands.startSoftAp(creds(band = Band.UNKNOWN), 34).size, "no -b option when the band is unknown")
    }

    @Test fun parsesTheOutputOfEachAndroidVersion() {
        fun p(out: String, code: Int = 0) = ShellCommands.parseStart(ShellResult(code, out, ""))
        assertEquals(ShellCommands.Outcome.SUCCEEDED, p("Soft AP started successfully"))                       // Android 11
        assertEquals(ShellCommands.Outcome.SUCCEEDED, p("onStateChanged with state: 13 SAP is enabled successfully")) // 12+
        assertEquals(ShellCommands.Outcome.FAILED, p("Soft AP failed to start. Please check config parameters"))
        assertEquals(ShellCommands.Outcome.FAILED, p("onStateChanged ... SAP failed to start"))
        assertEquals(ShellCommands.Outcome.FAILED, p("", code = 255))
        assertEquals(ShellCommands.Outcome.UNKNOWN, p("onStateChanged with state: 11 SAP is disabled"))
        assertEquals(ShellCommands.Outcome.SUCCEEDED, ShellCommands.parseStop(ShellResult(0, "Soft AP stopped successfully", "")))
        assertEquals(ShellCommands.Outcome.FAILED, ShellCommands.parseStop(ShellResult(0, "Soft AP failed to stop", "")))
    }

    @Test fun probeFailsBeforeAndroid12() = runTest {
        val r = assertIs<ProbeResult.Unavailable>(make(FakeShell { ShellResult(0, help11, "") }, sdk = 30).probe())
        assertEquals(FailureKind.UNSUPPORTED, r.kind)
    }

    @Test fun probeChecksThatStartSoftapExists() = runTest {
        val ok = make(FakeShell { ShellResult(0, help12, "") }).probe()
        assertTrue(assertIs<ProbeResult.Available>(ok).detail.contains("wpa3 supported"))
        val missing = assertIs<ProbeResult.Unavailable>(make(FakeShell { ShellResult(0, "some other help", "") }).probe())
        assertEquals(FailureKind.UNSUPPORTED, missing.kind)
        val broken = assertIs<ProbeResult.Unavailable>(make(FakeShell { ShellResult(1, "", "cmd: Can't find service: wifi") }).probe())
        assertEquals(FailureKind.API_UNAVAILABLE, broken.kind)
        val timeout = assertIs<ProbeResult.Unavailable>(make(FakeShell { throw BackendException(FailureKind.TIMEOUT, "slow") }).probe())
        assertEquals(FailureKind.TIMEOUT, timeout.kind)
    }

    @Test fun probeNeedsShizuku() = runTest {
        val shell = FakeShell { ShellResult(0, help12, "") }
        val r = assertIs<ProbeResult.Unavailable>(make(shell, gateway = FakeGateway(ShizukuStatus(ShizukuState.NOT_RUNNING))).probe())
        assertEquals(FailureKind.SHIZUKU_NOT_RUNNING, r.kind)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun startWithoutCredentialsIsRefused() = runTest {
        val shell = FakeShell { ShellResult(0, "", "") }
        val e = assertFailsWith<BackendException> { make(shell).start(null, 5_000) }
        assertEquals(FailureKind.NO_CREDENTIALS, e.kind)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun startSucceedsOnTheSuccessMessageAndNeverLogsSecrets() = runTest {
        val log = RecordingLog()
        val shell = FakeShell { ShellResult(0, "onStateChanged ssid=SecretSSID SAP is enabled successfully", "") }
        val b = make(shell, log = log, wifi = FakeWifi(state = 13))
        b.start(creds("SecretSSID", "SecretPassw0rd"), 5_000)
        assertEquals(true, b.active.value)
        val text = log.text()
        assertFalse("SecretSSID" in text || "SecretPassw0rd" in text, text)
    }

    @Test fun startFailureMessageIsReportedAndRedacted() = runTest {
        val shell = FakeShell { ShellResult(0, "Soft AP failed to start. Please check config parameters for SecretSSID", "") }
        val e = assertFailsWith<BackendException> { make(shell).start(creds("SecretSSID", "SecretPassw0rd"), 5_000) }
        assertEquals(FailureKind.START_FAILED, e.kind)
        assertFalse("SecretSSID" in e.message!!)
    }

    @Test fun unrecognisedOutputIsResolvedByTheApState() = runTest {
        val up = make(FakeShell { ShellResult(0, "", "") }, wifi = FakeWifi(state = 13))
        up.start(creds(), 5_000)
        assertEquals(true, up.active.value)
        val down = make(FakeShell { ShellResult(0, "", "") }, wifi = FakeWifi(state = 11))
        assertEquals(FailureKind.START_FAILED, assertFailsWith<BackendException> { down.start(creds(), 5_000) }.kind)
    }

    @Test fun stopRunsStopSoftap() = runTest {
        val shell = FakeShell { ShellResult(0, "Soft AP stopped successfully", "") }
        val b = make(shell)
        b.stop()
        assertEquals(listOf(ShellCommands.STOP_SOFTAP), shell.commands)
        assertEquals(false, b.active.value)
    }

    @Test fun stopFailureWhileApStillUpIsAnError() = runTest {
        val b = make(FakeShell { ShellResult(0, "Soft AP failed to stop", "") }, wifi = FakeWifi(state = 13))
        assertFailsWith<BackendException> { b.stop() }
    }

    @Test fun clientsAreUnknown() = runTest {
        assertEquals(null, make(FakeShell { ShellResult(0, "", "") }).clients.value)
    }
}
