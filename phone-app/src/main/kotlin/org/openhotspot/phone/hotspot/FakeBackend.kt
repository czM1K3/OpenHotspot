package org.openhotspot.phone.hotspot

import kotlinx.coroutines.delay
import org.openhotspot.common.Band
import org.openhotspot.common.HotspotInfo
import org.openhotspot.common.HotspotState
import org.openhotspot.common.Security
import org.openhotspot.common.phone.EngineLog
import org.openhotspot.common.phone.HotspotController
import org.openhotspot.common.phone.LogLevel

/**
 * FAKE hotspot backend for Batch 3. It starts no hotspot and uses neither Shizuku nor any
 * tethering API: a valid request just "succeeds" and returns made-up credentials.
 * The real backend (Shizuku, per-Android-version tethering calls) is a later batch.
 */
class FakeBackend(private val log: EngineLog = EngineLog.NONE, private val simulatedDelayMs: Long = 300) : HotspotController {
    @Volatile private var active = false

    override fun isReady() = true
    override fun isActive() = active

    override suspend fun enable(options: Int, autoOffSeconds: Int): HotspotInfo {
        delay(simulatedDelayMs)
        active = true
        log.log(LogLevel.INFO, "FAKE backend: pretending the hotspot is on (nothing was started)")
        return info()
    }

    override suspend fun disable() {
        active = false
        log.log(LogLevel.INFO, "FAKE backend: pretending the hotspot is off")
    }

    override suspend fun query(): HotspotInfo = if (active) info() else HotspotInfo(HotspotState.OFF)

    private fun info() = HotspotInfo(HotspotState.ON, Security.WPA2_PSK, Band.GHZ_2_4, FAKE_SSID.toByteArray(), FAKE_PASSPHRASE)

    companion object {
        const val FAKE_SSID = "OpenHotspot-FAKE"
        const val FAKE_PASSPHRASE = "fake-passphrase-1234"
    }
}
