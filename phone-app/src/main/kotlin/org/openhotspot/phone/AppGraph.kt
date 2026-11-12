package org.openhotspot.phone

import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.openhotspot.common.phone.HotspotController
import org.openhotspot.common.phone.PhoneEngine
import org.openhotspot.common.phone.RateLimiter
import org.openhotspot.common.phone.SystemClock
import org.openhotspot.phone.data.AndroidKeystoreCipher
import org.openhotspot.phone.data.EncryptedClientStore
import org.openhotspot.phone.data.LogBuffer
import org.openhotspot.phone.data.RateLimitPrefs
import org.openhotspot.phone.data.Settings
import org.openhotspot.phone.hotspot.FakeBackend

/** What the UI shows about the foreground service and BLE. */
data class ServiceStatus(
    val running: Boolean = false,
    val bluetooth: String = "Service stopped",
    val advertising: Boolean = false,
    val connections: Int = 0,
)

/** Hand-wired singletons (no DI framework: fewer dependencies, easy to read). */
class AppGraph(context: Context) {
    val log = LogBuffer()
    val settings = Settings(context)
    val store = EncryptedClientStore(File(context.noBackupFilesDir, "clients.bin"), AndroidKeystoreCipher(), log)

    /** Batch 3: FAKE backend. Swap for the Shizuku-backed controller in a later batch. */
    val hotspot: HotspotController = FakeBackend(log)

    val engine = PhoneEngine(
        store, hotspot, SystemClock, log = log,
        limiter = RateLimiter(SystemClock, RateLimitPrefs(context)),
    )
    val serviceStatus = MutableStateFlow(ServiceStatus())
}

class OpenHotspotApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as OpenHotspotApp).graph
