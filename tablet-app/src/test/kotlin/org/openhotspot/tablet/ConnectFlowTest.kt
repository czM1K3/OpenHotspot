package org.openhotspot.tablet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.PhoneException
import org.openhotspot.tablet.client.HotspotClient
import org.openhotspot.tablet.client.HotspotNotReadyException
import org.openhotspot.tablet.client.PhoneNotFoundException
import org.openhotspot.tablet.data.AppSettings
import org.openhotspot.tablet.data.JoinMethod
import org.openhotspot.tablet.flow.ConnectFlow
import org.openhotspot.tablet.flow.NoInternetException
import org.openhotspot.tablet.flow.Stage
import org.openhotspot.tablet.wifi.ProbeResult
import org.openhotspot.tablet.wifi.WifiJoinException
import org.openhotspot.tablet.wifi.WifiProblem

class ConnectFlowTest {
    private class Rig(scope: TestScope) {
        val clock = scope.virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone; nowMs = { scope.testScheduler.currentTime } }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        val wifi = FakeWifi()
        val stages = mutableListOf<Stage>()
        val flow = ConnectFlow(client, { wifi }, ConnectFlow.Timing(), sleep = { kotlinx.coroutines.delay(it) })
    }

    private suspend fun Rig.paired() {
        pairWith(client, phone)
        ble.scans = 0; ble.connects = 0 // count only what the flow itself does
    }
    private suspend fun Rig.run(settings: AppSettings = AppSettings()) = flow.run(store.list(), null, settings) { stages += it }

    @Test fun happyPathEnablesJoinsAndVerifies() = runTest {
        val r = Rig(this); r.paired()
        val c = r.run()
        assertEquals(ProbeResult.Online, c.internet)
        assertEquals(1, r.phone.hotspot.enableCalls)
        assertEquals(1, r.wifi.joins)
        assertEquals("Test Phone", r.wifi.lastSpec!!.ssid.toString(Charsets.UTF_8))
        assertEquals("correct horse battery", r.wifi.lastSpec!!.passphrase)
        assertEquals(listOf(Stage.SCANNING, Stage.CONNECTING, Stage.REQUESTING, Stage.JOINING, Stage.VERIFYING), r.stages)
        assertFalse(r.wifi.links.single().released) // the caller owns the live link
    }

    @Test fun waitsForAStartingHotspotByQuerying() = runTest {
        val r = Rig(this); r.paired()
        r.phone.hotspot.startingPolls = 3
        r.run()
        assertTrue(Stage.STARTING_HOTSPOT in r.stages)
        assertEquals(3, r.phone.hotspot.queryCalls)
        assertEquals(1, r.wifi.joins)
    }

    @Test fun aHotspotThatNeverComesOnIsReportedAndNeverJoined() = runTest {
        val r = Rig(this); r.paired()
        r.phone.hotspot.neverOn = true
        assertFailsWith<HotspotNotReadyException> { r.run() }
        assertEquals(0, r.wifi.joins)
    }

    @Test fun noPhoneNearbyMeansNoPhoneNotFound() = runTest {
        val r = Rig(this); r.paired()
        r.ble.inRange.clear()
        assertFailsWith<PhoneNotFoundException> { r.run() }
        assertEquals(0, r.wifi.joins)
    }

    @Test fun noPairedPhoneMeansNotFoundWithoutScanning() = runTest {
        val r = Rig(this)
        assertFailsWith<PhoneNotFoundException> { r.run() }
        assertEquals(0, r.ble.scans)
    }

    @Test fun aDroppedLinkRetriesWithANewConnectionAndANewCounter() = runTest {
        val r = Rig(this); r.paired()
        val before = r.store.list().single().lastCounter
        r.ble.dropOnWrite = 1 // the first connection dies on its first write
        r.run()
        assertEquals(2, r.ble.connects)
        assertEquals(2, r.ble.scans) // fresh scan for the retry
        // First attempt burned one counter (stored before sending), the retry used the next one.
        assertEquals(before + 2, r.store.list().single().lastCounter)
        assertEquals(1, r.phone.hotspot.enableCalls)
    }

    @Test fun retriesAreBoundedAndThenTheLinkErrorSurfaces() = runTest {
        val r = Rig(this); r.paired()
        r.ble.failConnects = 5
        val e = assertFailsWith<org.openhotspot.tablet.ble.BleException> { r.run() }
        assertEquals(2, r.ble.connects) // two attempts, no more
        assertTrue(e.retryable)
    }

    @Test fun errorsThePhoneReportsAreNotRetried() = runTest {
        val r = Rig(this); r.paired()
        r.phone.hotspot.failure = ErrorCode.HOTSPOT_FAILED
        val e = assertFailsWith<PhoneException> { r.run() }
        assertEquals(ErrorCode.HOTSPOT_FAILED, e.code)
        assertEquals(1, r.ble.connects)
    }

    @Test fun joinFailureSurfacesAndLeavesNothingBehind() = runTest {
        val r = Rig(this); r.paired()
        r.wifi.joinFailure = WifiJoinException(WifiProblem.NOT_AVAILABLE)
        val e = assertFailsWith<WifiJoinException> { r.run() }
        assertEquals(WifiProblem.NOT_AVAILABLE, e.problem)
        assertTrue(r.wifi.links.isEmpty())
    }

    @Test fun verifyKeepsProbingUntilTheInternetAnswers() = runTest {
        val r = Rig(this); r.paired()
        r.wifi.probes = mutableListOf(ProbeResult.Offline("timeout"), ProbeResult.CaptivePortal, ProbeResult.Online)
        val c = r.run()
        assertEquals(ProbeResult.Online, c.internet)
        assertEquals(3, r.wifi.links.single().probeCalls)
    }

    @Test fun noInternetHandsTheLiveLinkToTheCaller() = runTest {
        val r = Rig(this); r.paired()
        r.wifi.probes = mutableListOf(ProbeResult.Offline("UnknownHostException"))
        val e = assertFailsWith<NoInternetException> { r.run() }
        assertEquals(ProbeResult.Offline("UnknownHostException"), e.connected.internet)
        assertFalse(r.wifi.links.single().released)
        assertTrue(r.wifi.links.single().probeCalls > 1) // it really retried before giving up
    }

    @Test fun settingsChooseTheJoinMethodAndHiddenFlag() = runTest {
        val r = Rig(this); r.paired()
        var asked: JoinMethod? = null
        val flow = ConnectFlow(r.client, { asked = it; r.wifi })
        flow.run(r.store.list(), null, AppSettings(joinMethod = JoinMethod.SUGGESTION, hiddenSsid = true)) { }
        assertEquals(JoinMethod.SUGGESTION, asked)
        assertTrue(r.wifi.lastSpec!!.hidden)
    }

    @Test fun cancellingWhileVerifyingReleasesTheNetwork() = runTest {
        val r = Rig(this); r.paired()
        r.wifi.probes = mutableListOf(ProbeResult.Offline("x"))
        val job = backgroundScope.launch { r.run() }
        testScheduler.advanceTimeBy(3_000)
        job.cancel()
        job.join()
        assertTrue(r.wifi.links.single().released)
    }
}
