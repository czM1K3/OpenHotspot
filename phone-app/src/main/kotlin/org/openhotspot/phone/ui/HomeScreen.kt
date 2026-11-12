package org.openhotspot.phone.ui

import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.openhotspot.phone.graph
import org.openhotspot.phone.permissions.BatteryOptimization
import org.openhotspot.phone.permissions.BlePermissions
import org.openhotspot.phone.service.HotspotService

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val graph = ctx.graph
    val status by graph.serviceStatus.collectAsState()
    var refresh by remember { mutableIntStateOf(0) } // bumped on resume so the checklist re-reads system state
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    @Suppress("UNUSED_EXPRESSION") refresh
    val hasBt = BlePermissions.hasBluetooth(ctx)
    val hasNotif = BlePermissions.hasNotifications(ctx)
    val btOn = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    val batteryOk = BatteryOptimization.isIgnoring(ctx)
    var startOnBoot by remember { mutableStateOf(graph.settings.startOnBoot) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val missing = BlePermissions.missing(ctx)

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("OpenHotspot", style = MaterialTheme.typography.headlineMedium)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Fake backend", style = MaterialTheme.typography.titleMedium)
                Text("This build does not start a real hotspot. A valid request returns made-up credentials. The Shizuku backend comes in a later batch.")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Service", style = MaterialTheme.typography.titleMedium)
                Text(if (status.running) "Running" else "Stopped")
                Text("Bluetooth: ${status.bluetooth}")
                Text("Connected centrals: ${status.connections}")
                if (status.running) {
                    OutlinedButton(onClick = { HotspotService.stop(ctx) }) { Text("Stop service") }
                } else {
                    Button(onClick = { HotspotService.start(ctx) }, enabled = hasBt) { Text("Start service") }
                    if (!hasBt) Text("Grant the Bluetooth permission first.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleMedium)
                CheckRow("Bluetooth permission", hasBt, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Needed to advertise and accept connections." else "Granted at install on this Android version.")
                CheckRow("Notifications", hasNotif, "Needed to show the always-on notification (Android 13+).")
                if (missing.isNotEmpty()) {
                    Button(onClick = { permissionLauncher.launch(missing.toTypedArray()) }) { Text("Grant permissions") }
                }
                CheckRow("Bluetooth turned on", btOn, "The service waits until Bluetooth is on.")
                CheckRow("Battery optimisation exempt", batteryOk, "Keeps Android from stopping the service in the background.")
                if (!batteryOk) {
                    Button(onClick = { ctx.startActivity(BatteryOptimization.requestIntent(ctx)) }) { Text("Exempt from battery optimisation") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Start after reboot")
                        Text("Only if the service was running before.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = startOnBoot, onCheckedChange = { startOnBoot = it; graph.settings.startOnBoot = it })
                }
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, ok: Boolean, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(if (ok) "✓" else "✗", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
