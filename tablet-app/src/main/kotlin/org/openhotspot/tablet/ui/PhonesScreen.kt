package org.openhotspot.tablet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.openhotspot.tablet.data.PhoneRecord
import org.openhotspot.tablet.graph

@Composable
fun PhonesScreen(onPair: () -> Unit) {
    val g = LocalContext.current.graph
    val phones by g.store.phones.collectAsState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<PhoneRecord?>(null) }
    var removing by remember { mutableStateOf<PhoneRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Paired phones", style = MaterialTheme.typography.headlineMedium) }
        if (phones.isEmpty()) item { Text("None yet.") }
        items(phones, key = { it.phoneId }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ID ${p.phoneId} · paired ${DateFormat.getDateInstance().format(Date(p.pairedAtMs))} · shown on the phone as \"${p.ourLabel}\"",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { renaming = p }) { Text("Rename") }
                        TextButton(onClick = { removing = p }, enabled = !busy) { Text("Unpair") }
                    }
                }
            }
        }
        item { Button(onClick = onPair) { Text("Pair a new phone") } }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.tertiary) } }
        if (busy) item { Text("Telling the phone…") }
    }

    renaming?.let { p ->
        var text by remember(p.phoneId) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename phone") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) g.store.rename(p.phoneId, text.trim()); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Unpair ${p.name}?") },
            text = { Text("The key is deleted from this tablet. If the phone is in range it is also told to forget this tablet; otherwise remove the tablet in the phone app too.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    busy = true
                    scope.launch {
                        val acked = g.phones.unpair(p.phoneId)
                        busy = false
                        message = if (acked) "Unpaired ${p.name}." else "Unpaired ${p.name} on this tablet. The phone could not be reached, so remove the tablet in its app as well."
                    }
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
