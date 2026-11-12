package org.openhotspot.phone.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuTetheringBackendTest {
    private class FakeTethering : TetheringApi {
        var prepareFailure: BackendException? = null
        var supported = true
        var startResult: TetherStart? = TetherStart.Started
        var onStart: () -> Unit = {}
        var current: TetheringListener? = null
        val calls = mutableListOf<String>()
        override fun prepare(): String { prepareFailure?.let { throw it }; return "fake-signature" }
        override fun isTetheringSupported() = supported
        override suspend fun startWifi(): TetherStart {
            calls += "start"; onStart()
            return startResult ?: run { delay(1_000_000); TetherStart.Started }
        }
        override fun stopWifi() { calls += "stop"; current?.onWifiActive(false) }
        override fun setListener(listener: TetheringListener?) { current = listener }
        override fun close() { calls += "close" }
    }

    private fun TestScope.make(gateway: FakeGateway = FakeGateway(), api: FakeTethering = FakeTethering(), wifi: FakeWifi = FakeWifi()) =
        Triple(
            ShizukuTetheringBackend(gateway, api, wifi, io = Dispatchers.Unconfined, clock = TestClock(mono = { testScheduler.currentTime })),
            api, wifi,
        )

    @Test fun probeReportsEachShizukuProblemWithItsKind() = runTest {
        for ((state, kind) in listOf(
            ShizukuState.NOT_INSTALLED to FailureKind.SHIZUKU_NOT_RUNNING,
            ShizukuState.NOT_RUNNING to FailureKind.SHIZUKU_NOT_RUNNING,
            ShizukuState.UNSUPPORTED_VERSION to FailureKind.SHIZUKU_TOO_OLD,
            ShizukuState.NOT_AUTHORIZED to FailureKind.SHIZUKU_NOT_AUTHORIZED,
        )) {
            val (b, api) = make(FakeGateway(ShizukuStatus(state)))
            val r = assertIs<ProbeResult.Unavailable>(b.probe())
            assertEquals(kind, r.kind, state.name)
            assertTrue(api.calls.isEmpty(), "must not touch hidden APIs while Shizuku is unusable")
        }
    }

    @Test fun probeAvailableWhenTheManagerIsReachable() = runTest {
        val (b) = make()
        assertIs<ProbeResult.Available>(b.probe())
    }

    @Test fun probeReflectionFailureIsApiUnavailable() = runTest {
        val api = FakeTethering().apply { prepareFailure = BackendException(FailureKind.API_UNAVAILABLE, "NoSuchMethodException: startTethering") }
        val (b) = make(api = api)
        val r = assertIs<ProbeResult.Unavailable>(b.probe())
        assertEquals(FailureKind.API_UNAVAILABLE, r.kind)
        assertTrue("NoSuchMethod" in r.reason)
    }

    @Test fun probeUnsupportedWhenTheServiceSaysTetheringIsDisallowed() = runTest {
        val (b) = make(api = FakeTethering().apply { supported = false })
        assertEquals(FailureKind.UNSUPPORTED, assertIs<ProbeResult.Unavailable>(b.probe()).kind)
    }

    @Test fun startWaitsForTheApToComeUpViaTheCallback() = runTest {
        val api = FakeTethering()
        val (b) = make(api = api)
        api.onStart = { api.current?.onWifiActive(true) }
        assertEquals(null, b.start(null, 5_000))
        assertEquals(true, b.active.value)
        assertEquals(listOf("start"), api.calls)
    }

    @Test fun startFallsBackToPollingTheWifiServiceWhenNoCallbackArrives() = runTest {
        val wifi = FakeWifi(state = 11) // WIFI_AP_STATE_DISABLED
        val api = FakeTethering().apply { onStart = { wifi.state = 13 } }
        val (b) = make(api = api, wifi = wifi)
        b.start(null, 5_000)
        assertEquals(true, b.active.value)
    }

    @Test fun startTimesOutWhenTheApNeverComesUp() = runTest {
        val (b) = make(wifi = FakeWifi(state = 11))
        assertEquals(FailureKind.TIMEOUT, assertFailsWith<BackendException> { b.start(null, 2_000) }.kind)
    }

    @Test fun startTimesOutWhenTheCallbackNeverArrives() = runTest {
        val (b) = make(api = FakeTethering().apply { startResult = null })
        assertEquals(FailureKind.TIMEOUT, assertFailsWith<BackendException> { b.start(null, 2_000) }.kind)
    }

    @Test fun tetherErrorsAreClassified() = runTest {
        for ((code, kind) in listOf(14 to FailureKind.PERMISSION_DENIED, 15 to FailureKind.PERMISSION_DENIED, 3 to FailureKind.UNSUPPORTED, 5 to FailureKind.START_FAILED, 11 to FailureKind.START_FAILED)) {
            val (b) = make(api = FakeTethering().apply { startResult = TetherStart.Failed(code) })
            val e = assertFailsWith<BackendException> { b.start(null, 2_000) }
            assertEquals(kind, e.kind, "error $code")
            assertTrue(TetherErrors.name(code) in e.message!!)
        }
    }

    @Test fun duplicateRequestIsNotAFailureIfTheApComesUp() = runTest {
        val api = FakeTethering().apply { startResult = TetherStart.Failed(TetherErrors.DUPLICATE_REQUEST) }
        val (b) = make(api = api, wifi = FakeWifi(state = 13))
        b.start(null, 2_000)
        assertEquals(true, b.active.value)
    }

    @Test fun startIsSkippedWhenAlreadyActive() = runTest {
        val api = FakeTethering()
        val (b) = make(api = api)
        b.onWifiActive(true)
        b.start(null, 2_000)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun startRequiresShizuku() = runTest {
        val (b) = make(FakeGateway(ShizukuStatus(ShizukuState.NOT_AUTHORIZED)))
        assertEquals(FailureKind.SHIZUKU_NOT_AUTHORIZED, assertFailsWith<BackendException> { b.start(null, 1_000) }.kind)
    }

    @Test fun stopCallsStopTetheringAndClearsActive() = runTest {
        val api = FakeTethering()
        val (b) = make(api = api, wifi = FakeWifi(state = 11))
        b.onWifiActive(true)
        b.stop()
        assertTrue("stop" in api.calls)
        assertEquals(false, b.active.value)
    }

    @Test fun clientsFollowTheCallback() = runTest {
        val (b) = make()
        assertEquals(null, b.clients.value)
        b.onWifiClients(3); assertEquals(3, b.clients.value)
        b.onWifiClients(null); assertEquals(null, b.clients.value)
    }
}
