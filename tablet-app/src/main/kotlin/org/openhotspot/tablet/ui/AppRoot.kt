package org.openhotspot.tablet.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.openhotspot.tablet.graph
import org.openhotspot.tablet.perm.Permissions
import org.openhotspot.tablet.service.AutoConnectService

enum class Tab { CONNECT, PHONES, SETTINGS }

/**
 * [connectRequests] increases each time the Quick Settings tile wants a connection; a change starts
 * one (after asking for permissions if needed).
 */
@Composable
fun AppRoot(connectRequests: Int) {
    val ctx = LocalContext.current
    val g = ctx.graph
    var tab by rememberSaveable { mutableStateOf(Tab.CONNECT) }
    var pairing by rememberSaveable { mutableStateOf(false) }

    // One place that turns "connect" into "ask for permissions, then connect", for the button and the tile.
    var afterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = afterPermissions
        afterPermissions = null
        if (next != null && Permissions.hasAll(ctx, Permissions.forConnecting(Build.VERSION.SDK_INT))) next()
    }
    val connect: () -> Unit = {
        val start = { g.connection.connect() ; Unit }
        val missing = Permissions.missing(ctx, Permissions.forConnecting(Build.VERSION.SDK_INT))
        if (missing.isEmpty()) start() else { afterPermissions = start; launcher.launch(missing.toTypedArray()) }
    }
    LaunchedEffect(connectRequests) { if (connectRequests > 0) { tab = Tab.CONNECT; connect() } }

    // Keep the foreground service in step with the setting (it also survives reboots via BootReceiver).
    val settings by g.settings.settings.collectAsState()
    LaunchedEffect(settings.autoEnabled) {
        if (settings.autoEnabled && Permissions.hasAll(ctx, Permissions.forConnecting(Build.VERSION.SDK_INT))) {
            runCatching { AutoConnectService.start(ctx) }
        } else {
            AutoConnectService.stop(ctx)
        }
    }

    if (pairing) {
        PairScreen(onClose = { pairing = false })
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.CONNECT, { tab = Tab.CONNECT }, { Icon(Icons.Default.Home, null) }, label = { Text("Connect") })
                NavigationBarItem(tab == Tab.PHONES, { tab = Tab.PHONES }, { Icon(Icons.Default.Phone, null) }, label = { Text("Phones") })
                NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.CONNECT -> HomeScreen(onConnect = connect, onPair = { pairing = true })
                Tab.PHONES -> PhonesScreen(onPair = { pairing = true })
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
