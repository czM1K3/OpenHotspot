package org.openhotspot.tablet.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.openhotspot.tablet.R
import org.openhotspot.tablet.data.AppSettings
import org.openhotspot.tablet.data.JoinMethod
import org.openhotspot.tablet.graph
import org.openhotspot.tablet.perm.Permissions
import org.openhotspot.tablet.service.ConnectTileService
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settings.settings.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    val autoPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.hasAll(ctx, Permissions.forAutoService(Build.VERSION.SDK_INT))) g.settings.update { it.copy(autoEnabled = true) }
        else message = "Auto-connect needs Bluetooth (Nearby devices), Wi-Fi and notification access."
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("How to join the hotspot", style = MaterialTheme.typography.titleMedium)
        JoinOption(s, JoinMethod.SPECIFIER, "Quick, this app only",
            "Android asks you to confirm. Only OpenHotspot can use the connection; other apps keep using the tablet's normal network. Needs the app on screen.") { g.settings.update { st -> st.copy(joinMethod = it) } }
        JoinOption(s, JoinMethod.SUGGESTION, "Share with all apps",
            "Android decides when to connect, which can take a while, and asks you to approve the first suggestion in a notification. Then the whole tablet uses the hotspot.") { g.settings.update { st -> st.copy(joinMethod = it) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(s.hiddenSsid, { v -> g.settings.update { it.copy(hiddenSsid = v) } })
            Text("The phone's hotspot name is hidden")
        }

        HorizontalDivider()
        Text("Auto-connect", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(s.autoEnabled, { on ->
                if (!on) g.settings.update { it.copy(autoEnabled = false) }
                else {
                    val missing = Permissions.missing(ctx, Permissions.forAutoService(Build.VERSION.SDK_INT))
                    if (missing.isEmpty()) g.settings.update { it.copy(autoEnabled = true) } else autoPerms.launch(missing.toTypedArray())
                }
            })
            Text("Connect automatically when there is no internet and a paired phone is nearby")
        }
        Text("Keeps a notification and scans for your phone only while the tablet is offline. A system confirmation prompt may still appear (see SETUP.md).", style = MaterialTheme.typography.bodySmall)
        Text("Wait at least this long between automatic attempts:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.COOLDOWN_CHOICES_S.forEach { sec ->
                FilterChip(s.cooldownSeconds == sec, { g.settings.update { it.copy(cooldownSeconds = sec) } }, { Text(cooldownLabel(sec)) })
            }
        }

        HorizontalDivider()
        Text("Quick Settings tile", style = MaterialTheme.typography.titleMedium)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(onClick = {
                ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(ctx, ConnectTileService::class.java), ctx.getString(R.string.tile_label),
                    Icon.createWithResource(ctx, R.drawable.ic_stat_hotspot), ContextCompat.getMainExecutor(ctx),
                ) { }
            }) { Text("Add the tile") }
        } else {
            Text("Swipe down twice, tap the pencil (edit) icon and drag \"Phone hotspot\" into your tiles.")
        }

        HorizontalDivider()
        Text("Battery", style = MaterialTheme.typography.titleMedium)
        val ignoring = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        Text(if (ignoring) "Battery optimisation is off for this app." else "Auto-connect can be delayed or stopped by battery optimisation. Samsung tablets are strict about this (SETUP.md).")
        if (!ignoring) {
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Allow background use") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

fun cooldownLabel(seconds: Int): String = "${seconds / 60} min"

@Composable
private fun JoinOption(s: AppSettings, method: JoinMethod, title: String, text: String, onPick: (JoinMethod) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(s.joinMethod == method, role = Role.RadioButton, onClick = { onPick(method) }),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(s.joinMethod == method, onClick = null)
        Column { Text(title, style = MaterialTheme.typography.bodyLarge); Text(text, style = MaterialTheme.typography.bodySmall) }
    }
}
