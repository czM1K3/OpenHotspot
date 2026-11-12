package org.openhotspot.tablet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.openhotspot.tablet.auto.AutoConnectController
import org.openhotspot.tablet.auto.Connector
import org.openhotspot.tablet.auto.NetworkMonitor
import org.openhotspot.tablet.client.FoundPhone
import org.openhotspot.tablet.client.PhoneScanner
import org.openhotspot.tablet.data.AppSettings
import org.openhotspot.tablet.data.InMemorySettings
import org.openhotspot.tablet.data.PhoneRecord
import org.openhotspot.tablet.flow.ConnectionState
import org.openhotspot.tablet.flow.Trigger

class AutoConnectControllerTest {
    private class FakeMonitor(online: Boolean = false) : NetworkMonitor {
        val flow = MutableStateFlow(online)
        override val online: StateFlow<Boolean> get() = flow
    }

    private class FakeScanner(val scope: TestScope, var nearby: Boolean = false) : PhoneScanner {
        val scanTimes = mutableListOf<Long>()
        override suspend fun findPhone(phones: List<PhoneRecord>, timeoutMs: Long): FoundPhone? {
            scanTimes += scope.testScheduler.currentTime
            if (nearby) return FoundPhone(phones.first(), "AA", -50)
            kotlinx.coroutines.delay(timeoutMs)
            return null
        }
    }

    /** A connector whose attempts take [attemptMs] and succeed or fail as told. */
    private class FakeConnector(val scope: TestScope, val attemptMs: Long = 5_000) : Connector {
        private val flow = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val state: StateFlow<ConnectionState> get() = flow
        override var busy = false
        val attempts = mutableListOf<Pair<Long, Trigger>>()
        var succeed = true

        override fun connect(phoneId: String?, trigger: Trigger): Boolean {
            if (busy) return false
            busy = true
            attempts += scope.testScheduler.currentTime to trigger
            flow.value = ConnectionState.Working(org.openhotspot.tablet.flow.Stage.SCANNING, null)
            scope.backgroundScope.launch {
                kotlinx.coroutines.delay(attemptMs)
                busy = false
                flow.value = if (succeed) ConnectionState.Online("p") else ConnectionState.Failed(org.openhotspot.tablet.client.UserError("x"))
            }
            return true
        }
        fun networkLost() { flow.value = ConnectionState.Idle }
    }

    private class Rig(val scope: TestScope, online: Boolean = false, enabled: Boolean = true, nearby: Boolean = true, withPhone: Boolean = true) {
        val settings = InMemorySettings(AppSettings(autoEnabled = enabled, cooldownSeconds = 300))
        val store = newStore().also { if (withPhone) it.add(PhoneRecord("id1", ByteArray(32), ByteArray(32), "t", "Phone", 0)) }
        val monitor = FakeMonitor(online)
        val scanner = FakeScanner(scope, nearby)
        val connector = FakeConnector(scope)
        var canScan = true
        val timing = AutoConnectController.Timing()
        val controller = AutoConnectController(
            settings, store, connector, scanner, monitor, scope.virtualClock(), timing, canScan = { canScan },
        )
        fun start() { scope.backgroundScope.launch { controller.run() } }
    }

    private fun TestScope.minutes(m: Long) = testScheduler.advanceTimeBy(m * 60_000)

    @Test fun connectsWhenOfflineAndThePhoneIsNearby() = runTest {
        val r = Rig(this); r.start()
        minutes(1)
        assertEquals(1, r.connector.attempts.size)
        assertEquals(Trigger.AUTO, r.connector.attempts.single().second)
        // Not before the offline debounce.
        assertTrue(r.connector.attempts.single().first >= r.timing.offlineDebounceMs)
    }

    @Test fun neverScansWhileOnline() = runTest {
        val r = Rig(this, online = true); r.start()
        minutes(30)
        assertEquals(0, r.scanner.scanTimes.size)
        assertEquals(0, r.connector.attempts.size)
    }

    @Test fun aBriefDropDoesNotCostAScan() = runTest {
        val r = Rig(this, online = false, nearby = true)
        r.monitor.flow.value = false
        r.start()
        testScheduler.advanceTimeBy(5_000)
        r.monitor.flow.value = true // back online inside the debounce
        minutes(10)
        assertEquals(0, r.scanner.scanTimes.size)
    }

    @Test fun doesNothingWhenTheSettingIsOffOrNothingIsPaired() = runTest {
        val off = Rig(this, enabled = false); off.start()
        val none = Rig(this, withPhone = false); none.start()
        minutes(30)
        assertEquals(0, off.scanner.scanTimes.size + none.scanner.scanTimes.size)
    }

    @Test fun turningTheSettingOnStartsWatching() = runTest {
        val r = Rig(this, enabled = false); r.start()
        minutes(2)
        assertEquals(0, r.scanner.scanTimes.size)
        r.settings.update { it.copy(autoEnabled = true) }
        minutes(1)
        assertEquals(1, r.connector.attempts.size)
    }

    @Test fun cooldownBlocksAnotherAttemptEvenIfTheNetworkIsLostAgain() = runTest {
        val r = Rig(this); r.start()
        minutes(1)
        assertEquals(1, r.connector.attempts.size)
        r.connector.networkLost() // connection dropped, still offline, phone still nearby
        minutes(4) // 5 min cooldown has not passed since the attempt started
        assertEquals(1, r.connector.attempts.size)
        minutes(3)
        assertEquals(2, r.connector.attempts.size)
        val gap = r.connector.attempts[1].first - r.connector.attempts[0].first
        assertTrue(gap >= 300_000, "gap was $gap ms")
    }

    @Test fun aFailedAttemptIsNotRetriedBeforeTheCooldown() = runTest {
        val r = Rig(this); r.connector.succeed = false; r.start()
        minutes(4)
        assertEquals(1, r.connector.attempts.size)
        minutes(3)
        assertEquals(2, r.connector.attempts.size)
    }

    @Test fun noScanWhileAnAttemptIsRunningOrConnected() = runTest {
        val r = Rig(this); r.start()
        minutes(1)
        val scansAfterConnect = r.scanner.scanTimes.size
        minutes(30) // connected ("Online"): the system's own default network may still look offline
        assertEquals(scansAfterConnect, r.scanner.scanTimes.size)
    }

    @Test fun scansBackOffWhileThePhoneIsAway() = runTest {
        val r = Rig(this, nearby = false); r.start()
        minutes(120)
        val gaps = r.scanner.scanTimes.zipWithNext { a, b -> b - a }
        // Window 6 s + interval 60 s at first...
        assertEquals(66_000L, gaps.first())
        // ...then it grows, but never beyond the maximum interval plus the window.
        assertTrue(gaps.last() > gaps.first())
        assertTrue(gaps.all { it <= r.timing.maxScanIntervalMs + r.timing.scanDurationMs })
        // Far fewer scans than a fixed 66 s cadence would give (about 109 in two hours).
        assertTrue(r.scanner.scanTimes.size < 40, "scans: ${r.scanner.scanTimes.size}")
    }

    @Test fun backOffResetsOnceTheTabletIsOnlineAgain() = runTest {
        val r = Rig(this, nearby = false); r.start()
        minutes(60)
        r.monitor.flow.value = true
        minutes(1)
        r.monitor.flow.value = false
        val n = r.scanner.scanTimes.size
        minutes(5)
        val fresh = r.scanner.scanTimes.drop(n)
        assertTrue(fresh.size >= 2, "expected quick scans again, got ${fresh.size}")
        assertEquals(66_000L, fresh[1] - fresh[0])
    }

    @Test fun noScanningWhileBluetoothOrPermissionsAreMissing() = runTest {
        val r = Rig(this); r.canScan = false; r.start()
        minutes(10)
        assertEquals(0, r.scanner.scanTimes.size)
        r.canScan = true
        minutes(2)
        assertEquals(1, r.connector.attempts.size)
    }
}
