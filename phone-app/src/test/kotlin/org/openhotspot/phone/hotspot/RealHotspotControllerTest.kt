package org.openhotspot.phone.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.HotspotState
import org.openhotspot.common.phone.HotspotException

@OptIn(ExperimentalCoroutinesApi::class)
class RealHotspotControllerTest {
    private class Rig(
        scope: CoroutineScope,
        val tether: StubBackend = StubBackend(BackendId.SHIZUKU_TETHERING),
        val shell: StubBackend = StubBackend(BackendId.SHELL_COMMAND),
        val local: StubBackend = StubBackend(BackendId.LOCAL_ONLY),
        val wifi: FakeWifi = FakeWifi(),
        val settings: FakeSettings = FakeSettings(),
        clock: TestClock = TestClock(),
    ) {
        val log = RecordingLog()
        val controller = RealHotspotController(
            listOf(tether, shell, local), wifi, settings, scope, clock, log, io = Dispatchers.Unconfined,
        )
    }

    @Test fun choosesTheFirstAvailableBackendInOrder() = runTest {
        val r = Rig(backgroundScope)
        r.tether.probeResult = unavailable(FailureKind.API_UNAVAILABLE)
        val report = r.controller.refresh()
        assertEquals(BackendId.SHELL_COMMAND, report.chosen)
        assertEquals(listOf(BackendId.SHELL_COMMAND, BackendId.LOCAL_ONLY), report.available)
        assertTrue(r.controller.isReady())
    }

    @Test fun notReadyWhenNothingIsAvailable() = runTest {
        val r = Rig(backgroundScope, local = StubBackend(BackendId.LOCAL_ONLY, probeResult = unavailable(FailureKind.PERMISSION_DENIED)))
        r.tether.probeResult = unavailable(FailureKind.SHIZUKU_NOT_RUNNING)
        r.shell.probeResult = unavailable(FailureKind.SHIZUKU_NOT_RUNNING)
        r.controller.refresh()
        assertFalse(r.controller.isReady())
    }

    @Test fun localOnlyIsSkippedWhenDisabledInSettings() = runTest {
        val r = Rig(backgroundScope, settings = FakeSettings(localOnly = false))
        r.tether.probeResult = unavailable(FailureKind.SHIZUKU_NOT_RUNNING)
        r.shell.probeResult = unavailable(FailureKind.SHIZUKU_NOT_RUNNING)
        val report = r.controller.refresh()
        assertEquals(null, report.chosen)
        assertEquals("disabled in Settings", report.entries.last().skipped)
        assertFalse(r.local.calls.contains("probe"))
    }

    @Test fun aThrowingProbeBecomesUnavailableInsteadOfCrashing() = runTest {
        val r = Rig(backgroundScope)
        r.tether.probeThrows = IllegalStateException("boom")
        val e = r.controller.refresh().entries.first()
        val res = assertNotNull(e.result as? ProbeResult.Unavailable)
        assertEquals(FailureKind.INTERNAL, res.kind)
        assertEquals(BackendId.SHELL_COMMAND, r.controller.report.value?.chosen)
    }

    @Test fun enableUsesTheChosenBackendAndReportsSystemCredentials() = runTest {
        val r = Rig(backgroundScope)
        val info = r.controller.enable(0, 0)
        assertEquals(HotspotState.ON, info.state)
        assertEquals("MyPhone", String(info.ssid))
        assertEquals(listOf("probe", "start"), r.tether.calls)
        assertTrue(r.shell.calls.none { it == "start" })
        assertEquals(BackendId.SHIZUKU_TETHERING, r.controller.activeBackend.value)
        assertTrue(r.controller.isActive())
    }

    @Test fun fallsBackToTheNextBackendWhenStartFails() = runTest {
        val r = Rig(backgroundScope)
        r.tether.onStart = { throw BackendException(FailureKind.START_FAILED, "tethering error 5") }
        r.settings.user = creds()
        val info = r.controller.enable(0, 0)
        assertEquals(HotspotState.ON, info.state)
        assertEquals(BackendId.SHELL_COMMAND, r.controller.activeBackend.value)
        assertTrue(r.tether.calls.contains("stop"), "the half-started backend must be stopped before falling back")
        assertTrue(r.log.text().contains("Falling back"))
    }

    @Test fun fallsAllTheWayToLocalOnlyWithItsOwnCredentials() = runTest {
        val r = Rig(backgroundScope)
        r.tether.onStart = { throw BackendException(FailureKind.API_UNAVAILABLE, "reflection blocked") }
        r.shell.onStart = { throw BackendException(FailureKind.START_FAILED, "failed") }
        r.local.onStart = { creds("AndroidShare_1234", "lohs-secret-pass", source = CredentialSource.BACKEND) }
        val info = r.controller.enable(0, 0)
        assertEquals("AndroidShare_1234", String(info.ssid))
        assertEquals(BackendId.LOCAL_ONLY, r.controller.activeBackend.value)
        assertTrue(r.log.text().contains("NO internet sharing"), "the log must say that local-only has no internet")
    }

    @Test fun missingCredentialsStopTheChainInsteadOfFallingToLocalOnly() = runTest {
        val r = Rig(backgroundScope, wifi = FakeWifi(ConfigRead.Unavailable("unreadable")))
        r.shell.probeResult = unavailable(FailureKind.UNSUPPORTED)
        val e = assertFailsWith<HotspotException> { r.controller.enable(0, 0) }
        assertEquals(ErrorCode.HOTSPOT_FAILED, e.code)
        assertTrue(r.tether.calls.none { it == "start" })
        assertTrue(r.local.calls.none { it == "start" }, "must not silently start a hotspot without internet")
    }

    @Test fun credentialPolicyDecidesBetweenSystemAndUserEntries() = runTest {
        val sys = creds("SystemName", "system-pass-1", source = CredentialSource.SYSTEM)
        val usr = creds("UserName", "user-pass-123")
        val r = Rig(backgroundScope, wifi = FakeWifi(ConfigRead.Found(sys)), settings = FakeSettings(user = usr))
        // SYSTEM_FIRST (tethering): the OS starts from its own config, so report that.
        assertEquals("SystemName", String(r.controller.enable(0, 0).ssid))
        r.controller.disable()
        // USER_FIRST (shell): the user's entry is what gets configured.
        r.tether.probeResult = unavailable(FailureKind.API_UNAVAILABLE)
        r.controller.refresh()
        assertEquals("UserName", String(r.controller.enable(0, 0).ssid))
        assertEquals("UserName", String(r.shell.startedWith!!.ssid))
    }

    @Test fun systemFirstFallsBackToUserEntryWhenTheConfigCannotBeRead() = runTest {
        val r = Rig(backgroundScope, wifi = FakeWifi(ConfigRead.Unavailable("denied")), settings = FakeSettings(user = creds("UserName", "user-pass-123")))
        assertEquals("UserName", String(r.controller.enable(0, 0).ssid))
        assertTrue(r.log.text().contains("must match"))
    }

    @Test fun errorCodeFollowsTheShizukuProblemWhenNothingIsUsable() = runTest {
        val r = Rig(backgroundScope, settings = FakeSettings(localOnly = false))
        r.tether.probeResult = unavailable(FailureKind.SHIZUKU_NOT_RUNNING)
        r.shell.probeResult = unavailable(FailureKind.SHIZUKU_NOT_RUNNING)
        assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, assertFailsWith<HotspotException> { r.controller.enable(0, 0) }.code)

        r.tether.probeResult = unavailable(FailureKind.SHIZUKU_NOT_AUTHORIZED)
        r.shell.probeResult = unavailable(FailureKind.SHIZUKU_NOT_AUTHORIZED)
        r.controller.refresh()
        assertEquals(ErrorCode.PERMISSION_DENIED, assertFailsWith<HotspotException> { r.controller.enable(0, 0) }.code)
        assertNotNull(r.controller.lastError.value)
    }

    @Test fun allBackendsFailingReportsTheFirstFailure() = runTest {
        val r = Rig(backgroundScope)
        r.tether.onStart = { throw BackendException(FailureKind.PERMISSION_DENIED, "tethering error 14") }
        r.shell.onStart = { throw BackendException(FailureKind.START_FAILED, "x") }
        r.local.onStart = { throw BackendException(FailureKind.START_FAILED, "y") }
        r.settings.user = creds()
        assertEquals(ErrorCode.PERMISSION_DENIED, assertFailsWith<HotspotException> { r.controller.enable(0, 0) }.code)
        assertFalse(r.controller.isActive())
    }

    @Test fun enableOnRestrictsToOneBackendWithoutFallback() = runTest {
        val r = Rig(backgroundScope)
        r.tether.onStart = { throw BackendException(FailureKind.START_FAILED, "x") }
        assertFailsWith<HotspotException> { r.controller.enableOn(BackendId.SHIZUKU_TETHERING, 0) }
        assertTrue(r.shell.calls.none { it == "start" })
    }

    @Test fun cancellingAStartStopsTheBackend() = runTest {
        val r = Rig(backgroundScope)
        r.tether.onStart = { delay(60_000); null }
        assertFailsWith<TimeoutCancellationException> { withTimeout(20_000) { r.controller.enable(0, 0) } }
        assertTrue(r.tether.calls.contains("stop"), "an enable abandoned by the 20 s protocol timeout must not leave the hotspot on")
        assertFalse(r.controller.isActive())
    }

    @Test fun disableStopsTheActiveBackendAndQueryReportsOff() = runTest {
        val r = Rig(backgroundScope)
        r.controller.enable(0, 0)
        assertEquals(HotspotState.ON, r.controller.query().state)
        r.controller.disable()
        assertFalse(r.controller.isActive())
        assertEquals(HotspotState.OFF, r.controller.query().state)
        assertEquals(null, r.controller.activeBackend.value)
    }

    @Test fun disableFailureSurfacesAsAProtocolError() = runTest {
        val r = Rig(backgroundScope)
        r.controller.enable(0, 0)
        r.tether.stopFailure = BackendException(FailureKind.PERMISSION_DENIED, "denied")
        assertEquals(ErrorCode.PERMISSION_DENIED, assertFailsWith<HotspotException> { r.controller.disable() }.code)
    }

    @Test fun externalSwitchOffIsReflectedInIsActive() = runTest {
        val r = Rig(backgroundScope)
        r.controller.enable(0, 0)
        r.tether.activeFlow.value = false // the user turned the hotspot off in Android's own settings
        assertFalse(r.controller.isActive())
    }

    @Test fun logNeverContainsCredentials() = runTest {
        val r = Rig(backgroundScope, wifi = FakeWifi(ConfigRead.Found(creds("SecretSSID", "SecretPassw0rd", source = CredentialSource.SYSTEM))))
        r.controller.enable(0, 0); r.controller.query(); r.controller.disable()
        val text = r.log.text()
        assertFalse(text.contains("SecretSSID") || text.contains("SecretPassw0rd"))
    }

    // ---- auto-off -------------------------------------------------------------------------

    private fun TestScope.rig(minutes: Int, setup: Rig.() -> Unit = {}): Rig =
        Rig(backgroundScope, settings = FakeSettings(autoOff = minutes), clock = TestClock(mono = { testScheduler.currentTime })).also(setup)

    @Test fun autoOffTurnsTheHotspotOffAfterTheIdlePeriod() = runTest {
        val r = rig(minutes = 5)
        r.controller.enable(0, 0)
        advanceTimeBy(4 * 60_000L); runCurrent()
        assertTrue(r.controller.isActive())
        advanceTimeBy(61_000L); runCurrent()
        assertFalse(r.controller.isActive())
        assertTrue(r.tether.calls.contains("stop"))
        assertTrue(r.log.text().contains("Auto-off"))
    }

    @Test fun aConnectedClientPausesAutoOffAndLeavingRestartsTheCountdown() = runTest {
        val r = rig(minutes = 5)
        r.controller.enable(0, 0)
        advanceTimeBy(3 * 60_000L); runCurrent()
        r.tether.clientsFlow.value = 2
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertTrue(r.controller.isActive(), "must stay on while a client is connected")
        assertTrue(r.controller.autoOff.value is AutoOffState.ClientsConnected)
        r.tether.clientsFlow.value = 0
        advanceTimeBy(4 * 60_000L); runCurrent()
        assertTrue(r.controller.isActive(), "the idle period restarts from zero, not from the original start")
        advanceTimeBy(61_000L); runCurrent()
        assertFalse(r.controller.isActive())
    }

    @Test fun autoOffNeverFiresWhenTheClientCountIsUnknown() = runTest {
        val r = rig(minutes = 1) { tether.clientsFlow.value = null }
        r.controller.enable(0, 0)
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertTrue(r.controller.isActive())
        assertEquals(AutoOffState.ClientCountUnknown, r.controller.autoOff.value)
    }

    @Test fun settingZeroDisablesAutoOffUnlessTheClientAsks() = runTest {
        val r = rig(minutes = 0)
        r.controller.enable(0, 0)
        advanceTimeBy(24 * 3600_000L); runCurrent()
        assertTrue(r.controller.isActive())
        r.controller.disable()
        r.controller.enable(0, 120) // the client asks for 2 minutes
        advanceTimeBy(125_000L); runCurrent()
        assertFalse(r.controller.isActive())
    }

    @Test fun manualDisableCancelsTheTimer() = runTest {
        val r = rig(minutes = 1)
        r.controller.enable(0, 0)
        r.controller.disable()
        assertEquals(AutoOffState.Off, r.controller.autoOff.value)
        val stopsBefore = r.tether.calls.count { it == "stop" }
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(stopsBefore, r.tether.calls.count { it == "stop" })
    }

    @Test fun policyTakesTheStricterOfRequestAndSetting() {
        assertEquals(0, AutoOffPolicy.effectiveSeconds(0, 0))
        assertEquals(600, AutoOffPolicy.effectiveSeconds(0, 10))
        assertEquals(300, AutoOffPolicy.effectiveSeconds(300, 10))
        assertEquals(600, AutoOffPolicy.effectiveSeconds(3600, 10), "a client cannot extend the owner's limit")
        assertEquals(3600, AutoOffPolicy.effectiveSeconds(3600, 0))
        assertEquals(AutoOffPolicy.MIN_SECONDS, AutoOffPolicy.effectiveSeconds(5, 0), "clamped up")
        assertEquals(AutoOffPolicy.MAX_SECONDS, AutoOffPolicy.effectiveSeconds(Int.MAX_VALUE, 0), "clamped down")
        assertEquals(AutoOffPolicy.MAX_SECONDS, AutoOffPolicy.effectiveSeconds(0, 100_000))
    }
}
