package org.openhotspot.phone.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private val TABS = listOf("Status" to "⌂", "Pairing" to "⊞", "Settings" to "⚙", "Diag" to "✚", "Log" to "≡")

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, (label, glyph) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(glyph) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(m)
            1 -> PairingScreen(m, active = tab == 1)
            2 -> SettingsScreen(m)
            3 -> DiagnosticsScreen(m)
            else -> LogScreen(m)
        }
    }
}
