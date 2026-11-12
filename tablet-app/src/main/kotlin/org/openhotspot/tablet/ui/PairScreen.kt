package org.openhotspot.tablet.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openhotspot.common.Keys
import org.openhotspot.common.PairingException
import org.openhotspot.common.PairingQr
import org.openhotspot.tablet.client.ErrorText
import org.openhotspot.tablet.client.Labels
import org.openhotspot.tablet.client.UserError
import org.openhotspot.tablet.graph
import org.openhotspot.tablet.perm.Permissions
import org.openhotspot.tablet.qr.CameraQrScanner
import org.openhotspot.tablet.qr.QrGate
import org.openhotspot.tablet.qr.hasAnyCamera

private sealed interface Phase {
    data object Scan : Phase
    data object Working : Phase
    data class Failed(val error: UserError) : Phase
    data class Done(val name: String) : Phase
}

@Composable
fun PairScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val cameraPresent = remember { hasAnyCamera(ctx) }
    val needed = remember { if (cameraPresent) Permissions.forPairing(Build.VERSION.SDK_INT) else Permissions.forConnecting(Build.VERSION.SDK_INT) }
    var granted by remember { mutableStateOf(Permissions.hasAll(ctx, needed)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = Permissions.hasAll(ctx, needed) }

    var phase by remember { mutableStateOf<Phase>(Phase.Scan) }
    var label by rememberSaveable { mutableStateOf(Labels.fit("${Build.MANUFACTURER} ${Build.MODEL}")) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var scanHint by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun pair(qr: PairingQr) {
        phase = Phase.Working
        job = scope.launch {
            val id = Keys.shortId(qr.phonePub)
            try {
                val rec = g.client.pair(qr, Labels.fit(label), "Phone ${id.take(4)}")
                phase = Phase.Done(rec.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = Phase.Failed(ErrorText.describe(e))
            }
        }
    }

    val gate = remember { QrGate({ pair(it) }, { scanHint = it }) }
    BackHandler { job?.cancel(); onClose() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pair a phone", style = MaterialTheme.typography.headlineMedium)
            Text("On the phone, open OpenHotspot and its pairing screen, then scan the QR code it shows. The code is valid for 2 minutes and works once.")
            when (val p = phase) {
                Phase.Scan -> {
                    OutlinedTextField(label, { label = it.take(32) }, label = { Text("Name shown on the phone for this tablet") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (cameraPresent && granted) {
                        Box(Modifier.fillMaxWidth().height(360.dp)) {
                            CameraQrScanner(gate, Modifier.fillMaxSize(), onCameraError = { scanHint = it })
                        }
                    } else {
                        if (cameraPresent) Text("Camera and Bluetooth access are needed to scan and pair.") else Text("This tablet has no camera. Paste the pairing link instead.")
                        Button(onClick = { launcher.launch(needed.toTypedArray()) }) { Text("Allow access") }
                    }
                    scanHint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    // The same code as text (for devices without a camera, and for testing).
                    OutlinedTextField(pasted, { pasted = it }, label = { Text("…or paste the pairing link (openhotspot://pair/…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(
                        enabled = pasted.isNotBlank() && granted,
                        onClick = {
                            try { pair(PairingQr.parse(pasted)) } catch (e: PairingException) { scanHint = e.message }
                        },
                    ) { Text("Pair with this link") }
                }
                Phase.Working -> {
                    CircularProgressIndicator()
                    Text("Pairing… keep the phone's pairing screen open and close by.")
                    OutlinedButton(onClick = { job?.cancel(); gate.reset(); phase = Phase.Scan }) { Text("Cancel") }
                }
                is Phase.Failed -> {
                    Text(p.error.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    p.error.hint?.let { Text(it) }
                    Text("If the QR code expired, open the pairing screen on the phone again for a new one.")
                    Button(onClick = { gate.reset(); scanHint = null; phase = Phase.Scan }) { Text("Scan again") }
                }
                is Phase.Done -> {
                    Text("Paired with ${p.name}", style = MaterialTheme.typography.titleMedium)
                    Text("You can close the pairing screen on the phone.")
                }
            }
            OutlinedButton(onClick = { job?.cancel(); onClose() }) { Text(if (phase is Phase.Done) "Done" else "Back") }
        }
    }
}
