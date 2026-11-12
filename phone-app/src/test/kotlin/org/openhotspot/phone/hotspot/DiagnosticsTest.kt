package org.openhotspot.phone.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.phone.data.LogEntry

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsTest {
    private fun rig(scope: kotlinx.coroutines.CoroutineScope, clock: TestClock, tether: StubBackend, log: RecordingLog) = RealHotspotController(
        listOf(tether), FakeWifi(ConfigRead.Found(creds("SecretSSID", "SecretPassw0rd", source = CredentialSource.SYSTEM))),
        FakeSettings(), scope, clock, log, io = Dispatchers.Unconfined,
    )

    @Test fun toggleTestPassesAndNeverRecordsSecrets() = runTest {
        val clock = TestClock(mono = { testScheduler.currentTime })
        val log = RecordingLog()
        val c = rig(backgroundScope, clock, StubBackend(BackendId.SHIZUKU_TETHERING), log)
        val runner = DiagnosticsRunner(c, clock, log, holdMs = 1_000, offTimeoutMs = 1_000)
        runner.run(null)
        val run = runner.last.value!!
        assertTrue(run.ok, run.steps.joinToString("\n"))
        assertEquals(listOf("Detect backends", "Enable hotspot", "Query"), run.steps.take(3).map { it.name })
        assertTrue(run.steps.last().name == "Verify off")
        val all = run.steps.joinToString { it.detail } + log.text()
        assertFalse("SecretSSID" in all || "SecretPassw0rd" in all)
        assertTrue("ssid=10 bytes" in all)
    }

    @Test fun failedEnableIsReportedAndCleanupStillRuns() = runTest {
        val clock = TestClock(mono = { testScheduler.currentTime })
        val b = StubBackend(BackendId.SHIZUKU_TETHERING).apply { onStart = { throw BackendException(FailureKind.START_FAILED, "tethering error 5") } }
        val c = rig(backgroundScope, clock, b, RecordingLog())
        val runner = DiagnosticsRunner(c, clock, holdMs = 1_000, offTimeoutMs = 1_000)
        runner.run(null)
        val run = runner.last.value!!
        assertFalse(run.ok)
        val enable = run.steps.first { it.name == "Enable hotspot" }
        assertFalse(enable.ok)
        assertTrue("tethering error 5" in enable.detail)
        assertTrue(run.steps.none { it.name == "Query" }, "no query after a failed enable")
        assertTrue(run.steps.any { it.name == "Disable hotspot" })
        assertFalse(runner.running.value)
    }

    @Test fun reportContainsTheEssentialsAndNoSecrets() {
        val device = DeviceInfo("Nothing", "A059", "Galaxian", "17", 37, "2026-10-05", "Nothing OS 5.0", "0.2.0")
        val report = SelectionReport(
            listOf(
                ProbeEntry(BackendId.SHIZUKU_TETHERING, ProbeResult.Unavailable(FailureKind.API_UNAVAILABLE, "NoSuchMethodException"), null, 12),
                ProbeEntry(BackendId.LOCAL_ONLY, null, "disabled in Settings", 0),
            ),
            0,
        )
        val text = DiagnosticsReport.format(
            5, device, ShizukuStatus(ShizukuState.READY, serverUid = 2000, serverVersion = 13), report, "autoOffMinutes=10",
            DiagRun(1, null, listOf(DiagStep("Enable hotspot", false, 40, "boom"))),
            listOf(LogEntry(1, 2, LogLevel.ERROR, "something failed")),
        )
        for (needle in listOf("Nothing A059", "Android 17 (API 37)", "Nothing OS 5.0", "READY", "uid=2000", "NoSuchMethodException", "disabled in Settings", "overall FAIL", "[FAIL] Enable hotspot", "something failed")) {
            assertTrue(needle in text, "missing '$needle' in:\n$text")
        }
    }
}
