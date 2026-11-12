package org.openhotspot.phone.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import org.openhotspot.common.phone.ClientRecord
import org.openhotspot.phone.graph

@Composable
fun PairingScreen(modifier: Modifier = Modifier, active: Boolean) {
    val ctx = LocalContext.current
    val graph = ctx.graph
    val engine = graph.engine
    val clients by graph.store.clients.collectAsState()
    val status by graph.serviceStatus.collectAsState()
    var qr by remember { mutableStateOf<String?>(null) }
    var remainingS by remember { mutableLongStateOf(0L) }
    var pendingRevoke by remember { mutableStateOf<ClientRecord?>(null) }
    var justPaired by remember { mutableStateOf<String?>(null) }
    var knownCount by remember { mutableIntStateOf(clients.size) }

    // The pairing window only stays open while this screen is in the foreground (PROTOCOL.md 4.2).
    fun close() { engine.closePairing(); qr = null }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { close() }
    DisposableEffect(active) {
        onDispose { if (!active) close() }
    }

    // Keep the QR from being captured by screenshots or screen recording while it is shown.
    DisposableEffect(qr != null) {
        val window = (ctx as? Activity)?.window
        if (qr != null) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Countdown, and detect that the window closed (success or expiry).
    LaunchedEffect(qr) {
        while (qr != null) {
            remainingS = engine.pairingRemainingMs() / 1000
            if (!engine.isPairingOpen()) { qr = null; break }
            delay(500)
        }
    }
    LaunchedEffect(clients.size) {
        if (clients.size > knownCount) justPaired = clients.last().label
        knownCount = clients.size
    }

    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Pair a device", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val code = qr
                    if (code == null) {
                        Text("Show a one-time QR code and scan it with the OpenHotspot client on your laptop or tablet. The code works for 2 minutes and for one device.")
                        if (!status.running) Text("Start the service on the Status tab first, or the client cannot find this phone.", color = MaterialTheme.colorScheme.error)
                        justPaired?.let { Text("Paired with “$it”.", color = MaterialTheme.colorScheme.primary) }
                        Button(onClick = { justPaired = null; qr = engine.openPairing().encode() }, enabled = status.running) { Text("Show pairing QR") }
                    } else {
                        QrCode(code, Modifier.size(260.dp))
                        Text("Expires in ${remainingS}s", style = MaterialTheme.typography.titleMedium)
                        Text("Anyone who sees this code during this time can pair. Keep it private.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { close() }) { Text("Cancel") }
                    }
                }
            }
        }
        item { Text("Paired devices", style = MaterialTheme.typography.titleLarge) }
        if (clients.isEmpty()) item { Text("No paired devices yet.") }
        items(clients, key = { it.id }) { c ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.titleMedium)
                        Text("Paired ${DateFormat.getDateTimeInstance().format(Date(c.pairedAtMs))}", style = MaterialTheme.typography.bodySmall)
                        Text("id ${c.id} · ${java.lang.Long.toUnsignedString(c.lastCounter)} requests", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { pendingRevoke = c }) { Text("Revoke") }
                }
            }
        }
    }

    pendingRevoke?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text("Revoke “${c.label}”?") },
            text = { Text("This device will no longer be able to turn on the hotspot. It would have to be paired again. If it already knows the hotspot password, change the password too.") },
            confirmButton = { TextButton(onClick = { engine.revoke(c.id); pendingRevoke = null }) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = { pendingRevoke = null }) { Text("Keep") } },
        )
    }
}

/** Renders [text] as a QR code (ZXing core, no Play Services) with a white quiet zone. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
    }
    Canvas(modifier.background(Color.White)) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
