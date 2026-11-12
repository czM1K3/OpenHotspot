package org.openhotspot.tablet

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.openhotspot.common.phone.SystemClock
import org.openhotspot.tablet.auto.AutoConnectController
import org.openhotspot.tablet.ble.GattTransport
import org.openhotspot.tablet.client.HotspotClient
import org.openhotspot.tablet.client.PhoneManager
import org.openhotspot.tablet.data.AndroidKeystoreCipher
import org.openhotspot.tablet.data.EncryptedPhoneStore
import org.openhotspot.tablet.data.JoinMethod
import org.openhotspot.tablet.data.PrefsSettings
import org.openhotspot.tablet.flow.ConnectFlow
import org.openhotspot.tablet.flow.ConnectionController
import org.openhotspot.tablet.perm.Permissions
import org.openhotspot.tablet.service.AndroidNetworkMonitor
import org.openhotspot.tablet.wifi.SpecifierJoiner
import org.openhotspot.tablet.wifi.SuggestionJoiner

/** Hand-wired singletons (no DI framework: fewer dependencies, easy to read). Nothing here logs secrets. */
class AppGraph(private val context: Context) {
    private val log: (String) -> Unit = { Log.i(TAG, it) }

    val settings = PrefsSettings(context)
    val store = EncryptedPhoneStore(File(context.noBackupFilesDir, "phones.bin"), AndroidKeystoreCipher()) { Log.w(TAG, it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val transport = GattTransport(context)
    val client = HotspotClient(transport, store, SystemClock, log = log)
    val phones = PhoneManager(client, store)

    private val specifier by lazy { SpecifierJoiner(context) }
    private val suggestion by lazy { SuggestionJoiner(context) }
    private val connectFlow = ConnectFlow(
        client, wifi = { if (it == JoinMethod.SPECIFIER) specifier else suggestion }, log = log,
    )
    val connection = ConnectionController(scope, store, settings, connectFlow, client, log)

    val networkMonitor = AndroidNetworkMonitor(context)
    val auto = AutoConnectController(
        settings, store, connection, client, networkMonitor, SystemClock,
        canScan = {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true &&
                Permissions.hasAll(context, Permissions.forConnecting(android.os.Build.VERSION.SDK_INT))
        },
        log = log,
    )

    private companion object { const val TAG = "OpenHotspot" }
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
