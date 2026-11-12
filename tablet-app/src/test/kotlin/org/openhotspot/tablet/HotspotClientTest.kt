package org.openhotspot.tablet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openhotspot.common.ErrorCode
import org.openhotspot.common.HotspotState
import org.openhotspot.common.Keys
import org.openhotspot.common.PhoneException
import org.openhotspot.tablet.ble.BleException
import org.openhotspot.tablet.ble.BleProblem
import org.openhotspot.tablet.client.HotspotClient

class HotspotClientTest {

    @Test fun pairingStoresTheKeyAndThePhoneAcceptsTheTablet() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)

        val rec = client.pair(phone.openPairing(), "Alice's tablet", "My phone")

        assertEquals(listOf(rec.phoneId), store.list().map { it.phoneId })
        assertEquals("Alice's tablet", store.list().single().ourLabel)
        assertEquals(1, phone.clients.list().size)
        assertEquals("Alice's tablet", phone.clients.list().single().label)
        // Both sides hold the same long-term key.
        assertTrue(phone.clients.list().single().ltk.contentEquals(rec.ltk))
    }

    @Test fun pairingFailsClearlyWhenThePhoneIsNotAdvertisingThatCode() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val other = FakePhone(clock, "AA:BB:CC:DD:EE:02")
        val ble = FakeBleTransport().apply { inRange += other }
        val client = HotspotClient(ble, newStore(), clock)
        val e = assertFailsWith<BleException> { client.pair(phone.openPairing(), "t", "p", scanTimeoutMs = 3_000) }
        assertEquals(BleProblem.TIMEOUT, e.problem)
        assertEquals(0, ble.connects)
    }

    @Test fun pairingWithAnExpiredQrIsRejectedByThePhone() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val client = HotspotClient(ble, newStore(), clock)
        val qr = phone.openPairing()
        kotlinx.coroutines.delay(121_000) // past the 120 s window
        // The phone no longer advertises the pairing token, so the scan finds nothing.
        assertFailsWith<BleException> { client.pair(qr, "t", "p", scanTimeoutMs = 1_000) }
        assertEquals(0, phone.clients.list().size)
    }

    @Test fun enableTurnsTheHotspotOnAndReturnsCredentials() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        pairWith(client, phone)

        val found = assertNotNull(client.findPhone(store.list(), 5_000))
        client.open(found).use { s ->
            val info = s.enable()
            assertEquals(HotspotState.ON, info.state)
            assertEquals("Test Phone", info.ssid.toString(Charsets.UTF_8))
            assertEquals("correct horse battery", info.passphrase)
        }
        assertEquals(1, phone.hotspot.enableCalls)
    }

    @Test fun smallMtuStillWorksThroughChunking() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock, mtu = 23) // 19-byte fragments: a response needs several notifications
        val ble = FakeBleTransport().apply { inRange += phone }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        pairWith(client, phone)
        client.open(client.findPhone(store.list(), 5_000)!!).use {
            assertEquals(23, it.mtu)
            assertEquals(HotspotState.ON, it.enable().state)
        }
    }

    @Test fun counterStrictlyIncreasesAndTheStoreAlwaysLeadsThePhone() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        val rec = pairWith(client, phone)
        val before = store.list().single().lastCounter
        client.open(client.findPhone(store.list(), 5_000)!!).use { s ->
            s.enable(); s.query(); s.query()
        }
        val after = store.list().single()
        assertEquals(before + 3, after.lastCounter)
        // The phone accepted all three, so its last_counter equals ours: never ahead of the tablet.
        assertEquals(after.lastCounter, phone.clients.list().single().lastCounter)
    }

    @Test fun aReplayedCounterIsRefusedByThePhone() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        val rec = pairWith(client, phone)
        client.open(client.findPhone(store.list(), 5_000)!!).use { it.enable() }
        // Simulate a restored backup: the tablet's counter goes back. The phone must refuse.
        store.add(rec.copy(lastCounter = 0))
        val e = assertFailsWith<PhoneException> { client.open(client.findPhone(store.list(), 5_000)!!).use { it.enable() } }
        assertEquals(ErrorCode.REPLAY, e.code)
        assertTrue(e.authenticated)
    }

    @Test fun phoneErrorsSurfaceWithTheirCode() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        pairWith(client, phone)
        phone.hotspot.failure = ErrorCode.SHIZUKU_UNAVAILABLE
        val e = assertFailsWith<PhoneException> { client.open(client.findPhone(store.list(), 5_000)!!).use { it.enable() } }
        assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, e.code)
    }

    @Test fun findPhoneRecognisesTheRightOneAmongSeveral() = runTest {
        val clock = virtualClock()
        val a = FakePhone(clock, "AA:00:00:00:00:01")
        val b = FakePhone(clock, "AA:00:00:00:00:02")
        val ble = FakeBleTransport().apply { inRange += listOf(a, b) }
        val store = newStore()
        val client = HotspotClient(ble, store, clock)
        val ra = pairWith(client, a)
        val rb = pairWith(client, b)
        ble.inRange.remove(a)
        val found = assertNotNull(client.findPhone(listOf(ra, rb), 2_000))
        assertEquals(rb.phoneId, found.phone.phoneId)
        assertEquals(b.address, found.address)
        ble.inRange.clear()
        assertNull(client.findPhone(listOf(ra, rb), 2_000))
    }

    @Test fun phoneIdIsTheShortIdOfThePhoneKey() = runTest {
        val clock = virtualClock()
        val phone = FakePhone(clock)
        val ble = FakeBleTransport().apply { inRange += phone }
        val client = HotspotClient(ble, newStore(), clock)
        val qr = phone.openPairing()
        val rec = client.pair(qr, "t", "p")
        assertEquals(Keys.shortId(qr.phonePub), rec.phoneId)
    }
}
