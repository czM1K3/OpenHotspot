package org.openhotspot.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.openhotspot.phone.graph
import org.openhotspot.phone.hotspot.AutoOffState
import org.openhotspot.phone.hotspot.BackendId
import org.openhotspot.phone.hotspot.ShizukuState

/** Shizuku status, the chosen backend, the local-only warning and auto-off state. Shown on the Status tab. */
@Composable
fun HotspotCard() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val shizuku by g.shizuku.status.collectAsState()
    val report by g.hotspotController.report.collectAsState()
    val activeBackend by g.hotspotController.activeBackend.collectAsState()
    val lastError by g.hotspotController.lastError.collectAsState()
    val autoOff by g.hotspotController.autoOff.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        g.shizuku.refresh()
        scope.launch { g.hotspotController.refresh() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The prominent warning: local-only gives clients no internet.
        if (report?.chosen == BackendId.LOCAL_ONLY || activeBackend == BackendId.LOCAL_ONLY) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No internet sharing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Only the local-only fallback is available, so a client that connects to this phone's hotspot will have a Wi-Fi link " +
                            "but NO internet. Make Shizuku ready (below) to get a real hotspot.",
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku", style = MaterialTheme.typography.titleMedium)
                val ok = shizuku.state == ShizukuState.READY
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ok) "✓" else "✗", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (shizuku.state) {
                            ShizukuState.READY -> "Ready" + (shizuku.serverUid?.let { if (it == 0) " (running as root)" else " (running as shell, uid $it)" } ?: "")
                            ShizukuState.NOT_AUTHORIZED -> "Running, not authorized"
                            ShizukuState.NOT_RUNNING -> "Not running"
                            ShizukuState.NOT_INSTALLED -> "Not installed"
                            ShizukuState.UNSUPPORTED_VERSION -> "Too old"
                        },
                    )
                }
                when (shizuku.state) {
                    ShizukuState.NOT_INSTALLED -> Text("Install the Shizuku app (shizuku.rikka.app), then start it.", style = MaterialTheme.typography.bodySmall)
                    ShizukuState.NOT_RUNNING -> {
                        Text(
                            "Open Shizuku and start it (Android 11+: turn on Developer options > Wireless debugging, then use 'Start via Wireless debugging'). " +
                                "Shizuku has to be started again after every reboot.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { g.shizuku.openManager() }) { Text("Open Shizuku") }
                    }
                    ShizukuState.UNSUPPORTED_VERSION -> Text("Update Shizuku to the latest version.", style = MaterialTheme.typography.bodySmall)
                    ShizukuState.NOT_AUTHORIZED -> if (shizuku.permanentlyDenied) {
                        Text("Permission was denied with 'don't ask again'. In Shizuku, open Authorized apps and enable OpenHotspot.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { g.shizuku.openManager() }) { Text("Open Shizuku") }
                    } else {
                        Button(onClick = { g.shizuku.requestPermission() }) { Text("Authorize OpenHotspot") }
                    }
                    ShizukuState.READY -> Unit
                }
                OutlinedButton(onClick = { g.shizuku.refresh(); scope.launch { g.hotspotController.refresh() } }) { Text("Check again") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hotspot backend", style = MaterialTheme.typography.titleMedium)
                val r = report
                if (r == null) Text("Detecting…") else {
                    Text(r.chosen?.let { "Using: ${it.label}" } ?: "No backend available", style = MaterialTheme.typography.bodyLarge)
                    for (e in r.entries) {
                        val mark = if (e.available) "✓" else if (e.skipped != null) "–" else "✗"
                        Text("$mark ${e.id.label}", style = MaterialTheme.typography.bodyMedium)
                        Text(e.describe(), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Detected ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.timeMs))}", style = MaterialTheme.typography.labelSmall)
                }
                activeBackend?.let { Text("Hotspot is ON via ${it.label}", style = MaterialTheme.typography.bodyMedium) }
                Text(
                    when (val a = autoOff) {
                        AutoOffState.Off -> "Auto-off: not running"
                        AutoOffState.ClientCountUnknown -> "Auto-off: unavailable (this backend cannot count clients)"
                        is AutoOffState.ClientsConnected -> "Auto-off: paused, ${a.count} client(s) connected"
                        is AutoOffState.Countdown -> "Auto-off: turns off at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(a.deadlineWallMs))} if no client joins"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { scope.launch { g.hotspotController.refresh() } }) { Text("Re-run detection") }
            }
        }
    }
}
