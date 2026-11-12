package org.openhotspot.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.openhotspot.phone.BuildConfig
import org.openhotspot.phone.graph
import org.openhotspot.phone.hotspot.BackendId
import org.openhotspot.phone.hotspot.DeviceInfo
import org.openhotspot.phone.hotspot.DiagnosticsReport

/** Builds the paste-back report. Contains no SSID, passphrase or key. */
fun Context.diagnosticsText(): String {
    val g = graph
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return DiagnosticsReport.format(
        nowMs = System.currentTimeMillis(),
        device = DeviceInfo.current(BuildConfig.VERSION_NAME),
        shizuku = g.shizuku.status.value,
        report = g.hotspotController.report.value,
        settingsSummary = "autoOffMinutes=${g.hotspotPrefs.autoOffMinutes()}, localOnlyAllowed=${g.hotspotPrefs.allowLocalOnly()}, " +
            "userCredentials=${if (g.hotspotPrefs.userCredentials() != null) "set" else "unset"}",
        run = g.diagnostics.last.value,
        log = g.log.entries.value,
        timeFmt = { fmt.format(Date(it)) },
    )
}

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val running by g.diagnostics.running.collectAsState()
    val last by g.diagnostics.last.collectAsState()
    val report by g.hotspotController.report.collectAsState()
    var target by remember { mutableStateOf<BackendId?>(null) }
    var copied by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test hotspot toggle", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Turns the hotspot ON for about 35 seconds (it holds 30 s so you can join it from another device), checks it, then turns it OFF. Your Wi-Fi connection may drop meanwhile. " +
                        "Nothing is sent over Bluetooth. Pick one backend to test it alone (no fallback), or Automatic to test the real order.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(target == null, { target = null }, label = { Text("Automatic") })
                    FilterChip(target == BackendId.SHIZUKU_TETHERING, { target = BackendId.SHIZUKU_TETHERING }, label = { Text("Tethering") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(target == BackendId.SHELL_COMMAND, { target = BackendId.SHELL_COMMAND }, label = { Text("Shell cmd") })
                    FilterChip(target == BackendId.LOCAL_ONLY, { target = BackendId.LOCAL_ONLY }, label = { Text("Local-only") })
                }
                Button(enabled = !running, onClick = { scope.launch { g.diagnostics.run(target) } }) {
                    Text(if (running) "Running…" else "Test hotspot toggle")
                }
            }
        }

        last?.let { run ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (run.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Last test: ${if (running) "running" else if (run.ok) "PASS" else "FAIL"} (${run.only ?: "automatic"})", style = MaterialTheme.typography.titleMedium)
                    for (s in run.steps) {
                        Text("${if (s.ok) "✓" else "✗"} ${s.name} (${s.ms} ms)", style = MaterialTheme.typography.bodyMedium)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Capability detection", style = MaterialTheme.typography.titleMedium)
                report?.entries?.forEach { Text("${it.id}: ${it.describe()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    ?: Text("Not run yet.")
                OutlinedButton(onClick = { scope.launch { g.hotspotController.refresh() } }) { Text("Re-run detection") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Export", style = MaterialTheme.typography.titleMedium)
                Text("Device and Android version, Shizuku state, capability detection, the last test and the log. Never contains the SSID, passphrase or keys.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "OpenHotspot diagnostics").putExtra(Intent.EXTRA_TEXT, ctx.diagnosticsText())
                        ctx.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Share") }
                    OutlinedButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenHotspot diagnostics", ctx.diagnosticsText()))
                        copied = true
                    }) { Text(if (copied) "Copied" else "Copy") }
                }
            }
        }
    }
}
