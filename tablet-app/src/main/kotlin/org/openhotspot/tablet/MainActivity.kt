package org.openhotspot.tablet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.openhotspot.tablet.ui.AppRoot
import org.openhotspot.tablet.ui.OpenHotspotTheme

class MainActivity : ComponentActivity() {
    /** Bumped each time the Quick Settings tile asks us to connect; the UI reacts to the change. */
    private var connectRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { OpenHotspotTheme { AppRoot(connectRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_CONNECT) connectRequests++
    }

    companion object {
        const val ACTION_CONNECT = "org.openhotspot.tablet.CONNECT"
    }
}
