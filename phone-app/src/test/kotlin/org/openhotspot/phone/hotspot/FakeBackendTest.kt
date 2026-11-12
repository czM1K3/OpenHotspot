package org.openhotspot.phone.hotspot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.openhotspot.common.HotspotState

class FakeBackendTest {
    @Test fun enableDisableAndQuery() = runBlocking<Unit> {
        val b = FakeBackend(simulatedDelayMs = 0)
        assertTrue(b.isReady()); assertFalse(b.isActive())
        assertEquals(HotspotState.OFF, b.query().state)
        val info = b.enable(0, 0)
        assertTrue(b.isActive())
        assertEquals(HotspotState.ON, info.state)
        assertEquals(info, b.query())
        b.disable()
        assertFalse(b.isActive())
        assertEquals(HotspotState.OFF, b.query().state)
    }

    @Test fun fakeCredentialsAreProtocolValid() = runBlocking<Unit> {
        val info = FakeBackend(simulatedDelayMs = 0).enable(0, 0)
        assertTrue(info.ssid.size in 1..32)
        assertTrue(info.passphrase.toByteArray().size in 8..63)
        info.encode() // would throw if invalid
    }
}
