package org.openhotspot.tablet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.openhotspot.tablet.data.JoinMethod
import org.openhotspot.tablet.flow.ConnectionState
import org.openhotspot.tablet.flow.Stage
import org.openhotspot.tablet.graph

/** Human wording for each stage. Pure, so it is unit tested. */
fun stageText(stage: Stage, joinMethod: JoinMethod): String = when (stage) {
    Stage.SCANNING -> "Looking for your phone…"
    Stage.CONNECTING -> "Connecting over Bluetooth…"
    Stage.REQUESTING -> "Asking the phone to turn on its hotspot…"
    Stage.STARTING_HOTSPOT -> "Waiting for the hotspot to start…"
    Stage.JOINING -> if (joinMethod == JoinMethod.SPECIFIER) "Joining the hotspot. Approve Android's \"Connect\" prompt…" else "Waiting for Android to join the hotspot…"
    Stage.VERIFYING -> "Checking that the internet works…"
}

@Composable
fun HomeScreen(onConnect: () -> Unit, onPair: () -> Unit) {
    val g = LocalContext.current.graph
    val state by g.connection.state.collectAsState()
    val notice by g.connection.notice.collectAsState()
    val phones by g.store.phones.collectAsState()
    val settings by g.settings.settings.collectAsState()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("OpenHotspot", style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (phones.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No phone paired yet", style = MaterialTheme.typography.titleMedium)
                        Text("Open the pairing screen in the OpenHotspot app on your phone, then scan its QR code here.")
                        Button(onClick = onPair) { Text("Pair a phone") }
                    }
                }
                return@Column
            }
            when (val s = state) {
                is ConnectionState.Working -> {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
                            Column {
                                Text(s.phoneName ?: "Your phone", style = MaterialTheme.typography.titleMedium)
                                Text(stageText(s.stage, settings.joinMethod))
                            }
                        }
                    }
                    OutlinedButton(onClick = { g.connection.cancel() }, Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                is ConnectionState.Online -> {
                    StatusCard("Connected to ${s.phoneName}", connectedNote(settings.joinMethod), good = true)
                    Button(onClick = { g.connection.disconnect() }, Modifier.fillMaxWidth().height(56.dp)) { Text("Disconnect and turn the hotspot off") }
                    OutlinedButton(onClick = { g.connection.disconnect(turnOffHotspot = false) }, Modifier.fillMaxWidth()) { Text("Leave, keep the phone's hotspot on") }
                }
                else -> {
                    if (s is ConnectionState.Failed) {
                        StatusCard(s.error.title, s.error.hint, good = false)
                        if (s.stillJoined) {
                            OutlinedButton(onClick = { g.connection.disconnect() }, Modifier.fillMaxWidth()) { Text("Disconnect") }
                        }
                    }
                    Button(onClick = onConnect, Modifier.fillMaxWidth().height(64.dp), enabled = !(s is ConnectionState.Failed && s.stillJoined)) {
                        Text(if (s is ConnectionState.Failed) "Try again" else "Connect to phone hotspot", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        if (phones.size == 1) "Phone: ${phones[0].name}" else "${phones.size} phones paired; the one in range is used.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
        }
    }
}

/** What the user must know about who can use the connection. */
fun connectedNote(method: JoinMethod): String = when (method) {
    JoinMethod.SPECIFIER -> "Internet check passed. This connection is available to OpenHotspot only; other apps keep using the tablet's normal network. Use \"Share with all apps\" in Settings for the whole tablet."
    JoinMethod.SUGGESTION -> "Internet check passed. The whole tablet uses this connection."
}

@Composable
private fun StatusCard(title: String, text: String?, good: Boolean) {
    val colors = if (good) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (text != null) { Spacer(Modifier.height(2.dp)); Text(text) }
        }
    }
}
