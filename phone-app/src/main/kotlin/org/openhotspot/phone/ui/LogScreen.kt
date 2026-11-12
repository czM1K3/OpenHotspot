package org.openhotspot.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.phone.graph

@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val log = LocalContext.current.graph.log
    val entries by log.entries.collectAsState()
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Log", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = { log.clear() }) { Text("Clear") }
        }
        Text("Kept in memory only. Never contains keys, SSIDs or passwords.", style = MaterialTheme.typography.bodySmall)
        if (entries.isEmpty()) Text("Nothing logged yet.")
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries.asReversed(), key = { it.id }) { e ->
                val color = when (e.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text("${fmt.format(Date(e.timeMs))}  ${e.message}", color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
