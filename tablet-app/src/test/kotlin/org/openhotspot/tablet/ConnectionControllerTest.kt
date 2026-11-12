package org.openhotspot.tablet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.openhotspot.tablet.client.HotspotClient
import org.openhotspot.tablet.data.InMemorySettings
import org.openhotspot.tablet.flow.ConnectFlow
import org.openhotspot.tablet.flow.ConnectionController
import org.openhotspot.tablet.flow.ConnectionState
import org.openhotspot.tablet.flow.Trigger
import org.openhotspot.tablet.wifi.ProbeResult

class ConnectionControllerTest {
    private class Rig(val scope: TestScope) {
        val clock = scope.virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        val wifi = FakeWifi()
        val logs = mutableListOf<String>()
        val controller = ConnectionController(
            scope, store, InMemorySettings(), ConnectFlow(client, { wifi }), client, { logs += it },
        )
    }

    private suspend fun Rig.paired() { pairWith(client, phone) }

    @Test fun connectsAndReportsOnline() = runTest {
        val r = Rig(this); r.paired()
        assertEquals(ConnectionState.Idle, r.controller.state.value)
        assertTrue(r.controller.connect())
        assertIs<ConnectionState.Working>(r.controller.state.value)
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.Online("Test phone"), r.controller.state.value)
    }

    @Test fun aSecondConnectWhileBusyOrConnectedIsIgnored() = runTest {
        val r = Rig(this); r.paired()
        assertTrue(r.controller.connect())
        assertFalse(r.controller.connect()) // still running
        testScheduler.advanceUntilIdle()
        assertFalse(r.controller.connect()) // already connected
        assertEquals(1, r.wifi.joins)
    }

    @Test fun failureBecomesAReadableError() = runTest {
        val r = Rig(this); r.paired()
        r.ble.inRange.clear()
        r.controller.connect()
        testScheduler.advanceUntilIdle()
        val s = assertIs<ConnectionState.Failed>(r.controller.state.value)
        assertEquals("Phone not found nearby", s.error.title)
        assertFalse(s.stillJoined)
        assertTrue(r.controller.connect()) // and a retry is possible
    }

    @Test fun userAttemptWithoutInternetStaysJoinedSoTheUserCanLookAndLeave() = runTest {
        val r = Rig(this); r.paired()
        r.wifi.probes = mutableListOf(ProbeResult.Offline("x"))
        r.controller.connect(null, Trigger.USER)
        testScheduler.advanceUntilIdle()
        val s = assertIs<ConnectionState.Failed>(r.controller.state.value)
        assertTrue(s.stillJoined)
        assertFalse(r.wifi.links.single().released)
        r.controller.disconnect()
        testScheduler.advanceUntilIdle()
        assertTrue(r.wifi.links.single().released)
        assertEquals(1, r.phone.hotspot.disableCalls)
        assertEquals(ConnectionState.Idle, r.controller.state.value)
    }

    @Test fun unattendedAttemptWithoutInternetUndoesEverything() = runTest {
        val r = Rig(this); r.paired()
        r.wifi.probes = mutableListOf(ProbeResult.Offline("x"))
        r.controller.connect(null, Trigger.AUTO)
        testScheduler.advanceUntilIdle()
        val s = assertIs<ConnectionState.Failed>(r.controller.state.value)
        assertFalse(s.stillJoined)
        assertTrue(r.wifi.links.single().released)
        assertEquals(1, r.phone.hotspot.disableCalls) // the phone's hotspot is switched off again
    }

    @Test fun disconnectLeavesWifiAndSwitchesTheHotspotOff() = runTest {
        val r = Rig(this); r.paired()
        r.controller.connect(); testScheduler.advanceUntilIdle()
        r.controller.disconnect()
        testScheduler.advanceUntilIdle()
        assertTrue(r.wifi.links.single().released)
        assertEquals(1, r.phone.hotspot.disableCalls)
        assertEquals(ConnectionState.Idle, r.controller.state.value)
        assertNull(r.controller.notice.value)
    }

    @Test fun disconnectKeepHotspotOnlyLeavesWifi() = runTest {
        val r = Rig(this); r.paired()
        r.controller.connect(); testScheduler.advanceUntilIdle()
        r.controller.disconnect(turnOffHotspot = false)
        testScheduler.advanceUntilIdle()
        assertTrue(r.wifi.links.single().released)
        assertEquals(0, r.phone.hotspot.disableCalls)
    }

    @Test fun disconnectWithThePhoneAwayStillLeavesWifiAndSaysSo() = runTest {
        val r = Rig(this); r.paired()
        r.controller.connect(); testScheduler.advanceUntilIdle()
        r.ble.inRange.clear()
        r.controller.disconnect()
        testScheduler.advanceUntilIdle()
        assertTrue(r.wifi.links.single().released)
        assertNotNull(r.controller.notice.value)
        assertEquals(ConnectionState.Idle, r.controller.state.value)
    }

    @Test fun theSystemDroppingTheNetworkReturnsToIdle() = runTest {
        val r = Rig(this); r.paired()
        r.controller.connect(); testScheduler.advanceUntilIdle()
        r.wifi.links.single().dropNetwork()
        assertEquals(ConnectionState.Idle, r.controller.state.value)
        assertTrue(r.controller.connect()) // free to connect again
    }

    @Test fun cancelStopsAnAttemptAndReturnsToIdle() = runTest {
        val r = Rig(this); r.paired()
        r.ble.inRange.clear() // the scan will sit for 15 s
        r.controller.connect()
        testScheduler.advanceTimeBy(1_000)
        r.controller.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.Idle, r.controller.state.value)
        assertFalse(r.controller.busy)
    }
}
