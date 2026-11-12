package org.openhotspot.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.openhotspot.phone.ui.AppRoot
import org.openhotspot.phone.ui.OpenHotspotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OpenHotspotTheme { AppRoot() } }
    }
}
