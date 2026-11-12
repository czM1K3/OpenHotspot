package org.openhotspot.phone.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openhotspot.common.Security
import org.openhotspot.phone.data.HotspotPrefs
import org.openhotspot.phone.graph
import org.openhotspot.phone.hotspot.ConfigRead

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val prefs = g.hotspotPrefs
    val scope = rememberCoroutineScope()

    val saved = remember { prefs.userCredentials() }
    var ssid by remember { mutableStateOf(saved?.let { String(it.ssid, Charsets.UTF_8) } ?: "") }
    var pass by remember { mutableStateOf(saved?.passphrase ?: "") }
    var security by remember { mutableStateOf(saved?.security ?: Security.WPA2_PSK) }
    var showPass by remember { mutableStateOf(false) }
    var credMessage by remember { mutableStateOf<String?>(null) }

    var autoOff by remember { mutableStateOf(prefs.autoOffMinutesSetting.toString()) }
    var allowLocal by remember { mutableStateOf(prefs.allowLocalOnlySetting) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { g.hotspotController.refresh() }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hotspot credentials", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The Shizuku backend starts the hotspot with the name and password saved in Android's hotspot settings and tries to read them. " +
                        "If that read fails, or for the shell-command backend, the values below are used. They must match the phone's hotspot settings. " +
                        "Stored encrypted with the Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID (hotspot name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Passphrase (8 to 63 characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(showPass, { showPass = it })
                    Text("Show passphrase")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Security.entries.filter { it != Security.OPEN }.forEach { s ->
                        FilterChip(selected = security == s, onClick = { security = s }, label = { Text(when (s) { Security.WPA2_PSK -> "WPA2"; Security.WPA3_SAE -> "WPA3"; else -> "WPA2/3" }) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { credMessage = prefs.saveUserCredentials(ssid, pass, security) ?: "Saved" }) { Text("Save") }
                    OutlinedButton(onClick = { prefs.clearUserCredentials(); ssid = ""; pass = ""; credMessage = "Cleared" }) { Text("Clear") }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { g.wifiApi.readSoftApConfig() }
                        when (r) {
                            is ConfigRead.Found -> {
                                ssid = String(r.credentials.ssid, Charsets.UTF_8)
                                pass = r.credentials.passphrase
                                security = r.credentials.security
                                credMessage = "Read from the phone (not saved yet: press Save to keep a copy)"
                            }
                            is ConfigRead.Unavailable -> credMessage = "Could not read: ${r.reason}"
                        }
                    }
                }) { Text("Read from phone (needs Shizuku)") }
                credMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto-off", style = MaterialTheme.typography.titleMedium)
                Text("Turn the hotspot off after this many minutes with no connected client. 0 = never. A client may ask for a shorter time, never a longer one.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    autoOff, { v -> autoOff = v.filter { it.isDigit() }.take(4); autoOff.toIntOrNull()?.let { prefs.autoOffMinutesSetting = it } },
                    label = { Text("Minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Needs a backend that can count clients (the TetheringManager backend can; the shell and local-only backends cannot).", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local-only fallback", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If no Shizuku backend works, Android's public local-only hotspot can still start. It has NO internet sharing, and the name and " +
                        "password are chosen by Android. Turn this off if you would rather get an error than a hotspot without internet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(allowLocal, { allowLocal = it; prefs.allowLocalOnlySetting = it; scope.launch { g.hotspotController.refresh() } })
                    Text("Allow local-only fallback")
                }
                OutlinedButton(onClick = {
                    permissionLauncher.launch(
                        arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION),
                    )
                }) { Text(if (Build.VERSION.SDK_INT >= 33) "Grant 'Nearby devices' permission" else "Grant Location permission") }
                Text("Only the local-only fallback needs this permission.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
