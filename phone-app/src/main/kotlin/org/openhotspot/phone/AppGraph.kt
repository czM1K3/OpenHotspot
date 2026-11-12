package org.openhotspot.phone

import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.openhotspot.common.phone.HotspotController
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.common.phone.PhoneEngine
import org.openhotspot.common.phone.RateLimiter
import org.openhotspot.common.phone.SystemClock
import org.openhotspot.phone.data.AndroidKeystoreCipher
import org.openhotspot.phone.data.EncryptedClientStore
import org.openhotspot.phone.data.LogBuffer
import org.openhotspot.phone.data.RateLimitPrefs
import org.openhotspot.phone.data.Settings
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openhotspot.phone.data.HotspotPrefs
import org.openhotspot.phone.hotspot.DiagnosticsRunner
import org.openhotspot.phone.hotspot.LocalOnlyBackend
import org.openhotspot.phone.hotspot.RealHotspotController
import org.openhotspot.phone.hotspot.ReflectiveTetheringApi
import org.openhotspot.phone.hotspot.ReflectiveWifiBinderApi
import org.openhotspot.phone.hotspot.ShellCommandBackend
import org.openhotspot.phone.hotspot.ShizukuGateway
import org.openhotspot.phone.hotspot.ShizukuGatewayImpl
import org.openhotspot.phone.hotspot.ShizukuShellRunner
import org.openhotspot.phone.hotspot.ShizukuTetheringBackend

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hotspotPrefs = HotspotPrefs(context, AndroidKeystoreCipher(), log)
    val shizuku: ShizukuGateway = ShizukuGatewayImpl(context) { log.add(LogLevel.INFO, it) }
    val wifiApi = ReflectiveWifiBinderApi()

    /** Real backends, in priority order. The controller probes them and falls back down this list. */
    val hotspotController = RealHotspotController(
        backends = listOf(
            ShizukuTetheringBackend(shizuku, ReflectiveTetheringApi(context, log), wifiApi, log),
            ShellCommandBackend(shizuku, ShizukuShellRunner(), wifiApi, Build.VERSION.SDK_INT, log),
            LocalOnlyBackend(context, log),
        ),
        wifi = wifiApi, settings = hotspotPrefs, scope = scope, clock = SystemClock, log = log,
    )
    val hotspot: HotspotController = hotspotController
    val diagnostics = DiagnosticsRunner(hotspotController, SystemClock, log)

    val engine = PhoneEngine(
        store, hotspot, SystemClock, log = log,
        limiter = RateLimiter(SystemClock, RateLimitPrefs(context)),
    )
    val serviceStatus = MutableStateFlow(ServiceStatus())

    init {
        // Capability detection runs at start and whenever Shizuku appears, dies or is (de)authorized.
        scope.launch { shizuku.status.collect { hotspotController.refresh() } }
    }
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
